package com.grahamcrockford.oco.api.mq;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.grahamcrockford.oco.spi.Job;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class JobPublisher {

  private final ConnectionFactory connectionFactory;
  private final ObjectMapper objectMapper;

  @Inject
  JobPublisher(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    this.connectionFactory = connectionFactory;
    this.objectMapper = objectMapper;
  }

  public void publishJob(Job job) {
    try (Connection connection = connectionFactory.newConnection();
         Channel channel = connection.createChannel()) {

       byte[] message = objectMapper.writeValueAsBytes(job);

       channel.queueDeclare(Queue.JOB, true, false, false, null);
       channel.basicPublish("", Queue.JOB, null, message);

    } catch (IOException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }
}