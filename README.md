# Logging & Monitoring Platform

A production-ready observability pipeline built with Spring Boot, Redis Streams, PostgreSQL, Prometheus, and Grafana. This platform provides centralized log ingestion, processing, metrics collection, and visualization for containerized applications.

## Architecture

```
┌─────────────┐
│  Producers  │ (Spring Boot)
│  (Multiple) │
└──────┬──────┘
       │ HTTP POST /api/logs
       │ JSON: {ts, app, level, msg, fields}
       ▼
┌─────────────────────────────────┐
│      Redis Streams              │
│  Stream: logs:stream            │
│  Consumer Group: log-processors │
└──────┬──────────────────────────┘
       │
       │ Consumer Group Processing
       ▼
┌─────────────────────────────────┐
│   Log Processor                 │
│   (Spring Boot + JPA)            │
│   - Batch Processing            │
│   - Ack-on-Write                │
│   - Fault Recovery              │
└──────┬──────────────────────────┘
       │
       │ Batch Inserts
       ▼
┌─────────────────────────────────┐
│   PostgreSQL                    │
│   - Indexed on ts, app, level   │
│   - JSONB fields storage        │
└─────────────────────────────────┘

       │
       │ Metrics Scraping
       ▼
┌─────────────────────────────────┐
│   Prometheus                    │
│   - Scrapes Actuator endpoints  │
│   - Stores time-series data     │
└──────┬──────────────────────────┘
       │
       │ Query & Visualize
       ▼
┌─────────────────────────────────┐
│   Grafana                       │
│   - Real-time dashboards        │
│   - Alerts & Monitoring         │
└─────────────────────────────────┘
```

## Features

- **High Throughput**: Processes 100+ logs/second with batch inserts
- **Fault Tolerant**: Automatic recovery of pending messages on processor restart
- **Zero Message Loss**: Ack-on-write ensures messages are only acknowledged after database persistence
- **Real-time Monitoring**: Grafana dashboards for logs/sec, stream lag, and latency
- **Scalable**: Supports multiple producers and processor instances
- **Observable**: Comprehensive metrics via Prometheus and Spring Boot Actuator

## Prerequisites

- Docker and Docker Compose
- Java 17+ (for local development)
- Maven 3.6+ (for local development)
- Python 3.7+ (for benchmarking scripts, optional)

## Setup

### Quick Start

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd logging-monitoring-platform
   ```

2. **Start all services**
   ```bash
   docker compose up --build
   ```

3. **Verify services are running**
   ```bash
   docker compose ps
   ```

4. **Access services**
   - Producer API: http://localhost:8080
   - Processor API: http://localhost:8081
   - Prometheus: http://localhost:9090
   - Grafana: http://localhost:3000 (admin/admin)
   - PostgreSQL: localhost:5432 (postgres/postgres)

### Service Details

| Service | Port | Description |
|---------|------|-------------|
| Producer | 8080 | Publishes logs to Redis Streams |
| Processor | 8081 | Consumes and processes logs |
| Redis | 6379 | Stream storage and message queue |
| PostgreSQL | 5432 | Persistent log storage |
| Prometheus | 9090 | Metrics collection |
| Grafana | 3000 | Visualization dashboards |

## Usage

### Publishing Logs

Send log messages to the producer API:

```bash
curl -X POST http://localhost:8080/api/logs \
  -H "Content-Type: application/json" \
  -d '{
    "ts": "2025-11-13T10:30:45.123Z",
    "app": "web-service",
    "level": "INFO",
    "msg": "Request processed successfully",
    "fields": {
      "userId": "12345",
      "requestId": "req-abc-123",
      "duration": 42
    }
  }'
```

### Viewing Metrics

**Prometheus:**
- Navigate to http://localhost:9090
- Query metrics: `rate(logs_processed_total[1m])`

**Grafana:**
- Login at http://localhost:3000 (admin/admin)
- Pre-configured dashboard: "Logging & Monitoring Platform"
- View: Logs/sec, Stream Lag, DB Write Latency

### Querying Logs

Connect to PostgreSQL and query logs:

```sql
-- Recent logs
SELECT * FROM logs ORDER BY ts DESC LIMIT 100;

-- Logs by application
SELECT * FROM logs WHERE app = 'web-service' ORDER BY ts DESC;

-- Error logs
SELECT * FROM logs WHERE level = 'ERROR' ORDER BY ts DESC;

-- Logs in time range
SELECT * FROM logs 
WHERE ts BETWEEN '2025-11-13 10:00:00' AND '2025-11-13 11:00:00'
ORDER BY ts DESC;
```

## Benchmarking

Run performance benchmarks:

```bash
cd benchmark
python3 benchmark.py
```

Results are exported to `benchmarks/benchmark_YYYYMMDD_HHMMSS.csv`

**Expected Performance:**
- **Low Load (10 msg/s)**: <5ms publish latency, <10ms DB write latency
- **Medium Load (50 msg/s)**: <10ms publish latency, <20ms DB write latency
- **High Load (100 msg/s)**: <20ms publish latency, <50ms DB write latency
- **Throughput**: 100+ logs/second sustained

See [benchmark/README.md](benchmark/README.md) for detailed benchmarking guide.

## Configuration

### Redis Stream Configuration

- **Stream Name**: `logs:stream`
- **Consumer Group**: `log-processors`
- **Consumer Name**: `processor-1`

### Database Configuration

- **Table**: `logs`
- **Indexes**: 
  - `idx_logs_ts_desc` on `ts DESC`
  - `idx_logs_app` on `app`
  - `idx_logs_level` on `level`
- **Batch Size**: 50 messages per batch

### Environment Variables

**Producer:**
- `REDIS_HOST`: Redis host (default: redis)
- `REDIS_PORT`: Redis port (default: 6379)

**Processor:**
- `REDIS_HOST`: Redis host (default: redis)
- `REDIS_PORT`: Redis port (default: 6379)
- `POSTGRES_HOST`: PostgreSQL host (default: postgres)
- `POSTGRES_PORT`: PostgreSQL port (default: 5432)
- `POSTGRES_DB`: Database name (default: logs)
- `POSTGRES_USER`: Database user (default: postgres)
- `POSTGRES_PASSWORD`: Database password (default: postgres)

## Testing

### Fault Recovery Test

Test processor recovery after failure:

```bash
chmod +x test-fault-recovery.sh
./test-fault-recovery.sh
```

See [FAULT_RECOVERY.md](FAULT_RECOVERY.md) for detailed testing guide.

### Health Checks

```bash
# Producer health
curl http://localhost:8080/actuator/health

# Processor health
curl http://localhost:8081/actuator/health

# Metrics
curl http://localhost:8080/actuator/prometheus
curl http://localhost:8081/actuator/prometheus
```

## Metrics

### Producer Metrics

- `logs_published_total`: Total logs published
- `logs_publish_latency_seconds`: Publish latency (histogram)

### Processor Metrics

- `logs_processed_total`: Total logs processed
- `logs_batches_processed_total`: Total batches processed
- `logs_db_write_latency_seconds`: Database write latency (histogram)
- `logs_stream_lag`: Pending messages in stream (gauge)
- `logs_recovery_count`: Number of recovery operations
- `logs_recovery_time`: Recovery time (timer)

## Architecture Decisions

### Why Redis Streams?

- **Durability**: Messages persist even if consumers are down
- **Consumer Groups**: Enable parallel processing and fault recovery
- **Ordering**: Maintains message order within a stream
- **Backpressure**: Natural backpressure via pending message lists

### Why Ack-on-Write?

- **Data Integrity**: Messages only acknowledged after successful database write
- **Fault Tolerance**: Unacknowledged messages are automatically retried
- **Zero Loss**: Guarantees no message loss even during failures

### Why Batch Processing?

- **Performance**: Reduces database round trips
- **Throughput**: Enables 100+ logs/second processing
- **Efficiency**: Optimizes database connection usage

### Why PostgreSQL?

- **ACID Compliance**: Ensures data consistency
- **JSONB Support**: Efficient storage of flexible log fields
- **Indexing**: Fast queries on timestamp, app, and level
- **Mature Ecosystem**: Well-supported and reliable

## Troubleshooting

### High Stream Lag

1. Check processor logs: `docker logs processor`
2. Verify database performance
3. Check for backpressure warnings in logs
4. Monitor DB write latency in Grafana

### Messages Not Processing

1. Verify consumer group exists:
   ```bash
   docker exec redis redis-cli XINFO GROUPS logs:stream
   ```
2. Check pending messages:
   ```bash
   docker exec redis redis-cli XPENDING logs:stream log-processors
   ```
3. Review processor logs for errors

### High Latency

1. Check database indexes are created
2. Verify batch size settings
3. Monitor system resources (CPU, memory)
4. Check database connection pool settings

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and design
- [FAULT_RECOVERY.md](FAULT_RECOVERY.md) - Fault recovery testing guide
- [benchmark/README.md](benchmark/README.md) - Benchmarking guide

## Roadmap

- [ ] Add log retention policies
- [ ] Implement log aggregation by app/level
- [ ] Add alerting rules in Grafana
- [ ] Support for multiple consumer groups
- [ ] Add log search functionality
- [ ] Implement log sampling for high-volume scenarios

**Key Technologies**: Spring Boot, Redis Streams, PostgreSQL, Prometheus, Grafana, Docker, Java 17
