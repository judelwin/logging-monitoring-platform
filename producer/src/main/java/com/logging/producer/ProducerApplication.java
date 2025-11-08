package com.logging.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Producer application that publishes JSON logs to Redis Streams.
 * Exposes metrics via Spring Boot Actuator for Prometheus scraping.
 */
@SpringBootApplication
public class ProducerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }
}

