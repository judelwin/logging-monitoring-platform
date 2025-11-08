package com.logging.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Processor application that consumes logs from Redis Streams
 * and persists them to PostgreSQL. Handles consumer groups for
 * parallel processing and recovery of pending messages.
 */
@SpringBootApplication
public class ProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProcessorApplication.class, args);
    }
}

