package com.gruelbox.orko.jobrun;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.gruelbox.orko.jobrun.JobAccess.JobAlreadyExistsException;
import com.gruelbox.orko.jobrun.spi.Job;
import com.gruelbox.orko.jobrun.spi.JobControl;
import com.gruelbox.orko.jobrun.spi.JobProcessor;
import com.gruelbox.orko.jobrun.spi.Status;
import com.gruelbox.orko.jobrun.spi.StatusUpdateService;

@Singleton
class JobRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobRunner.class);

  private final JobAccess jobAccess;
  private final JobLocker jobLocker;
  private final UUID uuid;
  private final Injector injector;
  private final EventBus eventBus;
  private final StatusUpdateService statusUpdateService;

  @Inject
  JobRunner(JobAccess advancedOrderAccess, JobLocker jobLocker,
            Injector injector, EventBus eventBus,
            StatusUpdateService statusUpdateService) {
    jobAccess = advancedOrderAccess;
    this.jobLocker = jobLocker;
    this.injector = injector;
    this.eventBus = eventBus;
    this.statusUpdateService = statusUpdateService;
    uuid = UUID.randomUUID();
  }

  /**
   * Attempts to run a job that already exists.  Used by the poll loop.
   *
   * <p>Note that if the lock is successful, the job is only unlocked
   * on success or, if the job fails, due to the TTL removing it.
   * This creates an automatic delay on retries.</p>
   *
   * @param job The job.
   * @return True if the job could be locked and run successfully.
   */
  public boolean runExisting(Job job) {
    if (jobLocker.attemptLock(job.id(), uuid)) {
      job = jobAccess.load(job.id());
      new JobLifetimeManager(job).start();
      return true;
    } else {
      return false;
    }
  }

  /**
   * Attempts to insert and run a new job.
   *
   * <p>Given that inserting into the database guarantees that it will run at
   * some point, provides the ability to acknowledge this with a callback before
   * actually starting. This can be used to acknowledge the upstream
   * request.</p>
   *
   * <p>The request is ignored (and the callback called) if the job has already
   * been created, to avoid double-calling.</p>
   *
   * <p>Note that if the lock is successful, the job is only unlocked
   * on success or, if the job fails, due to the TTL removing it.
   * This creates an automatic delay on retries.</p>
   *
   * @param job The job.
   * @param ack The insertion callback.
   * @param reject If insertion failed
   * @throws Exception
   */
  public boolean runNew(Job job, ExceptionThrowingRunnable ack, ExceptionThrowingRunnable reject) throws Exception {
    if (!attemptLock(job, reject)) {
      return false;
    }
    if (!createJob(job, ack, reject)) {
      return false;
    }
    new JobLifetimeManager(job).start();
    return true;
  }

  private boolean attemptLock(Job job, ExceptionThrowingRunnable reject) throws Exception {
    boolean locked;
    try {
      locked = jobLocker.attemptLock(job.id(), uuid);
    } catch (Exception t) {
      reject.run();
      LOGGER.warn("Job " + job.id() + " could not be locked. Request rejected.");
      throw t;
    }
    return locked;
  }

  private boolean createJob(Job job, ExceptionThrowingRunnable ack, ExceptionThrowingRunnable reject) throws Exception {
    try {
      jobAccess.insert(job);
    } catch (JobAlreadyExistsException e) {
      LOGGER.info("Job " + job.id() + " already exists. Request ignored.");
      ack.run();
      jobLocker.releaseLock(job.id(), uuid);
      return false;
    } catch (Throwable t) {
      reject.run();
      jobLocker.releaseLock(job.id(), uuid);
      throw t;
    }
    ack.run();
    return true;
  }

  public interface ExceptionThrowingRunnable {
    public void run() throws Exception;
  }

  private enum JobStatus {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
  }

  private final class JobLifetimeManager implements JobControl {

    private final Job job;
    private final JobProcessor<Job> processor;
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.CREATED);

    JobLifetimeManager(Job job) {
      this.job = job;
      processor = JobProcessor.createProcessor(job, this, injector);
    }

    public void start() {
      start(false);
    }

    private void start(boolean replacement) {

      // Ensure this lifetime manager can only be used once (when replacing, we
      // create a new lifetime manager).
      if (!status.compareAndSet(JobStatus.CREATED, JobStatus.STARTING))
        throw new IllegalStateException("Job lifecycle status indicates re-use of lifetime manager: " + job);

      // We do record that a new startup is a replacement, though, so don't log
      // startup if that's the case
      if (!replacement)
        LOGGER.info(job + " starting...");

      // Attempt to run the startup method on the job and send a status update
      Status result = safeStart();
      if (!replacement || !result.equals(Status.RUNNING))
        statusUpdateService.status(job.id(), result);

      switch (result) {
        case FAILURE_PERMANENT:
        case SUCCESS:

          // If the job completed or failed permanently, delete it
          LOGGER.debug("{} finished immediately ({}), cleaning up", job, result);
          jobAccess.delete(job.id());
          safeStop();
          status.set(JobStatus.STOPPED);
          LOGGER.debug("{} cleaned up", job);
          break;

        case FAILURE_TRANSIENT:

          // Stop and let the job get picked up again
          LOGGER.warn(job + " temporary failure. Sending back to queue for retry");
          safeStop();
          LOGGER.debug("{} cleaned up", job);
          break;

        case RUNNING:

          // We are now running, so register for events
          register();
          break;

        default:
          throw new IllegalStateException("Unknown job status " + result);
      }
    }

    @Subscribe
    public void onKeepAlive(KeepAliveEvent keepAlive) {
      if (!status.get().equals(JobStatus.RUNNING))
        return;
      if (!jobLocker.updateLock(job.id(), uuid)) {
        LOGGER.debug("{} stopping due to loss of lock...", job);
        if (stopAndUnregister())
          LOGGER.debug("{} stopped due to loss of lock", job);
      }
    }

    @Subscribe
    public void stop(StopEvent stop) {
      LOGGER.debug("{} stopping due to shutdown", job);
      if (stopAndUnregister()) {
        jobLocker.releaseLock(job.id(), uuid);
        LOGGER.debug("{} stopped due to shutdown", job);
      }
    }

    @Override
    public void replace(Job newVersion) {
      LOGGER.debug("{} replacing...", job);
      if (!stopAndUnregister()) {
        LOGGER.warn("Replacement of job which is already shutting down: " + job);
        return;
      }
      jobAccess.update(newVersion);
      new JobLifetimeManager(newVersion).start(true);
      LOGGER.debug("{} replaced", newVersion);
    }

    @Override
    public void finish(Status status) {
      LOGGER.info(job + " finishing ({})...", status);
      statusUpdateService.status(job.id(), status);
      if (!stopAndUnregister()) {
        LOGGER.warn("Finish of job which is already shutting down: {}", job);
        return;
      }
      jobAccess.delete(job.id());
      LOGGER.info(job + " finished");
    }

    private synchronized void register() {
      if (status.compareAndSet(JobStatus.STARTING, JobStatus.RUNNING)) {
        eventBus.register(this);
        LOGGER.info(job + " started");
      }
    }

    private synchronized boolean stopAndUnregister() {
      if (status.compareAndSet(JobStatus.RUNNING, JobStatus.STOPPING)) {
        safeStop();
        eventBus.unregister(this);
        status.set(JobStatus.STOPPED);
        return true;
      } else if (status.compareAndSet(JobStatus.STARTING, JobStatus.STOPPED)) {
        return true;
      } else {
        LOGGER.debug("Stop of job which is already shutting down: {}", job);
        return false;
      }
    }

    private Status safeStart() {
      Status result;
      try {
        result = processor.start();
      } catch (Exception e) {
        LOGGER.error("Error in start() for job [{}].", e);
        result = Status.FAILURE_TRANSIENT;
      }
      return result;
    }

    private void safeStop() {
      try {
        processor.stop();
      } catch (Exception e) {
        LOGGER.error("Error in stop() for job [{}]. Cleanup may not be complete.", e);
      }
    }

    @Override
    public String toString() {
      return "JobSubmitter[" + job + "]";
    }
  }
}