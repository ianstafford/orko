import { createSelector } from "reselect"
import { isAlert, isStop } from "../util/jobUtils"
import * as jobTypes from "../services/jobTypes"

export const getJobs = state => state.job.jobs

export const getAlertJobs = createSelector(
  [getJobs],
  jobs => (jobs ? jobs.filter(job => isAlert(job)) : [])
)

export const getStopJobs = createSelector(
  [getJobs],
  jobs => (jobs ? jobs.filter(job => isStop(job)) : [])
)

export const getWatchJobs = createSelector(
  [getJobs],
  jobs => jobs ? jobs.filter(job => job.jobType === jobTypes.WATCH_JOB) : []
)