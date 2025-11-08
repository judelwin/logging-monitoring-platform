package com.logging.processor.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Log entity persisted to PostgreSQL.
 * Indexed on ts (descending), app, and level for efficient querying.
 */
@Entity
@Table(name = "logs", indexes = {
    @Index(name = "idx_ts_desc", columnList = "ts DESC"),
    @Index(name = "idx_app", columnList = "app"),
    @Index(name = "idx_level", columnList = "level")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Instant ts;
    
    @Column(nullable = false, length = 100)
    private String app;
    
    @Column(nullable = false, length = 10)
    private String level;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String msg;
    
    @Column(columnDefinition = "JSONB")
    private String fields;
}

