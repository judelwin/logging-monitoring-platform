# Benchmarking Guide

## Overview

The benchmarking suite measures throughput and latency of the logging platform under various load conditions. Results are exported to CSV format for analysis.

## Prerequisites

- Docker and Docker Compose installed
- Python 3.7+ (for Python script) or bash with `bc` command (for shell script)
- All services running: `docker compose up -d`

## Running Benchmarks

### Option 1: Python Script (Recommended)

The Python script provides better error handling and more accurate latency measurements:

```bash
cd benchmark
python3 benchmark.py
```

**Requirements:**
```bash
pip install requests
```

### Option 2: Bash Script

```bash
cd benchmark
chmod +x benchmark.sh
./benchmark.sh
```

**Requirements:**
- `bc` command: `sudo apt-get install bc` (Linux) or `brew install bc` (Mac)

## Scaling Producers

To test with multiple producer instances:

```bash
# Scale to 3 producers
docker compose up -d --scale producer=3

# Note: The benchmark scripts simulate multiple producers by sending
# concurrent requests, so scaling is optional for most tests
```

## Benchmark Tests

The suite runs the following tests:

1. **Low Load**: 10 msg/s for 30 seconds
2. **Medium Load**: 50 msg/s for 30 seconds
3. **High Load**: 100 msg/s for 30 seconds
4. **Very High Load**: 200 msg/s for 30 seconds
5. **Burst Load**: 500 msg/s for 10 seconds

## Output

Results are saved to `benchmarks/benchmark_YYYYMMDD_HHMMSS.csv` with the following columns:

- `timestamp`: Test execution timestamp
- `test_name`: Name of the test
- `producer_count`: Number of producers (simulated)
- `messages_per_second`: Target message rate
- `total_messages`: Total messages sent
- `duration_seconds`: Actual test duration
- `publish_latency_p50_ms`: 50th percentile publish latency
- `publish_latency_p95_ms`: 95th percentile publish latency
- `publish_latency_p99_ms`: 99th percentile publish latency
- `db_write_latency_p50_ms`: 50th percentile DB write latency
- `db_write_latency_p95_ms`: 95th percentile DB write latency
- `db_write_latency_p99_ms`: 99th percentile DB write latency
- `throughput_logs_per_sec`: Actual processing throughput
- `stream_lag_max`: Maximum stream lag observed

## Analyzing Results

### Using Excel/Google Sheets

1. Open the CSV file
2. Create charts for:
   - Throughput vs Message Rate
   - Latency (p95) vs Message Rate
   - Stream Lag vs Message Rate

### Using Python

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv('benchmarks/benchmark_20251112_143000.csv')

# Plot throughput
plt.figure(figsize=(10, 6))
plt.plot(df['messages_per_second'], df['throughput_logs_per_sec'], 'o-')
plt.xlabel('Message Rate (msg/s)')
plt.ylabel('Throughput (logs/s)')
plt.title('Throughput vs Message Rate')
plt.grid(True)
plt.show()

# Plot latency
plt.figure(figsize=(10, 6))
plt.plot(df['messages_per_second'], df['publish_latency_p95_ms'], 'o-', label='Publish p95')
plt.plot(df['messages_per_second'], df['db_write_latency_p95_ms'], 's-', label='DB Write p95')
plt.xlabel('Message Rate (msg/s)')
plt.ylabel('Latency (ms)')
plt.title('Latency vs Message Rate')
plt.legend()
plt.grid(True)
plt.show()
```

## Expected Results

Based on typical hardware:

- **Low Load (10 msg/s)**: 
  - Throughput: ~10 logs/s
  - Publish latency: <5ms
  - DB write latency: <10ms

- **Medium Load (50 msg/s)**:
  - Throughput: ~50 logs/s
  - Publish latency: <10ms
  - DB write latency: <20ms

- **High Load (100 msg/s)**:
  - Throughput: ~100 logs/s
  - Publish latency: <20ms
  - DB write latency: <50ms

- **Very High Load (200 msg/s)**:
  - Throughput: ~150-200 logs/s
  - Publish latency: <50ms
  - DB write latency: <100ms
  - Stream lag may increase

- **Burst Load (500 msg/s)**:
  - Throughput: Limited by processor capacity
  - Significant stream lag expected
  - Latency increases under load

## Troubleshooting

### Low Throughput

- Check processor logs: `docker logs processor`
- Verify database performance
- Check Redis connection
- Monitor stream lag in Grafana

### High Latency

- Check database indexes are created
- Verify batch size settings
- Monitor database connection pool
- Check system resources (CPU, memory)

### Script Errors

- Ensure services are running: `docker compose ps`
- Check service health: `curl http://localhost:8080/actuator/health`
- Verify metrics endpoints are accessible

## Custom Tests

To create custom benchmark tests, modify the test list in the script:

**Python:**
```python
tests = [
    ("custom_test", 1, 75, 60),  # (name, producers, rate, duration)
]
```

**Bash:**
```bash
run_test "custom_test" 1 75 60
```

