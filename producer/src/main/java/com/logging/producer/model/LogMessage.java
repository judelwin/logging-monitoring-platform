package com.logging.producer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Log message model matching the defined schema.
 * Fields: ts (timestamp), app (application), level (log level), msg (message), fields (additional context).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogMessage {
    @JsonProperty("ts")
    private String ts;
    
    @JsonProperty("app")
    private String app;
    
    @JsonProperty("level")
    private String level;
    
    @JsonProperty("msg")
    private String msg;
    
    @JsonProperty("fields")
    private Map<String, Object> fields;
    
    public static LogMessage create(String app, String level, String msg, Map<String, Object> fields) {
        return new LogMessage(
            Instant.now().toString(),
            app,
            level,
            msg,
            fields
        );
    }
}

