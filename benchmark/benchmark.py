#!/usr/bin/env python3
"""
Benchmarking script for Logging Platform
Measures throughput and latency with scaled producers
Exports results to CSV
"""

import time
import csv
import requests
import statistics
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Tuple
import os

# Configuration
PRODUCER_URL = "http://localhost:8080/api/logs"
PROCESSOR_METRICS = "http://localhost:8081/actuator/prometheus"
PRODUCER_METRICS = "http://localhost:8080/actuator/prometheus"
OUTPUT_DIR = "benchmarks"

# Create output directory
os.makedirs(OUTPUT_DIR, exist_ok=True)


def extract_metric(metric_name: str, url: str) -> float:
    """Extract metric value from Prometheus format"""
    try:
        response = requests.get(url, timeout=5)
        for line in response.text.split('\n'):
            if line.startswith(metric_name) and not line.startswith('#'):
                parts = line.split()
                if len(parts) >= 2:
                    return float(parts[1])
    except Exception as e:
        print(f"Warning: Could not extract {metric_name}: {e}")
    return 0.0


def get_metrics() -> Dict[str, float]:
    """Get current metrics from both services"""
    return {
        'publish_latency_p50': extract_metric('logs_publish_latency_seconds{quantile="0.5"}', PRODUCER_METRICS) * 1000,
        'publish_latency_p95': extract_metric('logs_publish_latency_seconds{quantile="0.95"}', PRODUCER_METRICS) * 1000,
        'publish_latency_p99': extract_metric('logs_publish_latency_seconds{quantile="0.99"}', PRODUCER_METRICS) * 1000,
        'db_write_latency_p50': extract_metric('logs_db_write_latency_seconds{quantile="0.5"}', PROCESSOR_METRICS) * 1000,
        'db_write_latency_p95': extract_metric('logs_db_write_latency_seconds{quantile="0.95"}', PROCESSOR_METRICS) * 1000,
        'db_write_latency_p99': extract_metric('logs_db_write_latency_seconds{quantile="0.99"}', PROCESSOR_METRICS) * 1000,
        'logs_processed_total': extract_metric('logs_processed_total', PROCESSOR_METRICS),
        'stream_lag': extract_metric('logs_stream_lag', PROCESSOR_METRICS),
    }


def send_message(message_id: int, rate: int) -> Tuple[int, float]:
    """Send a single log message and measure latency"""
    payload = {
        "ts": datetime.utcnow().isoformat() + "Z",
        "app": "benchmark-app",
        "level": "INFO",
        "msg": f"Benchmark message {message_id}",
        "fields": {"testId": message_id, "rate": rate}
    }
    
    start_time = time.time()
    try:
        response = requests.post(PRODUCER_URL, json=payload, timeout=5)
        latency = (time.time() - start_time) * 1000  # Convert to ms
        return message_id, latency if response.status_code == 200 else -1
    except Exception as e:
        print(f"Error sending message {message_id}: {e}")
        return message_id, -1


def send_messages_at_rate(rate: int, duration: int) -> Tuple[List[float], float]:
    """Send messages at specified rate for given duration"""
    total_messages = rate * duration
    delay = 1.0 / rate if rate > 0 else 0
    latencies = []
    
    print(f"  Sending {total_messages} messages at {rate} msg/s...")
    
    start_time = time.time()
    sent = 0
    
    for i in range(1, total_messages + 1):
        msg_id, latency = send_message(i, rate)
        if latency >= 0:
            latencies.append(latency)
        sent += 1
        
        # Rate limiting
        if sent < total_messages:
            time.sleep(delay)
    
    actual_duration = time.time() - start_time
    return latencies, actual_duration


def run_test(test_name: str, producer_count: int, messages_per_second: int, duration: int, results_file: str):
    """Run a single benchmark test"""
    print(f"\n{'='*60}")
    print(f"Running test: {test_name}")
    print(f"  Producers: {producer_count} (simulated)")
    print(f"  Rate: {messages_per_second} msg/s")
    print(f"  Duration: {duration} seconds")
    print(f"{'='*60}")
    
    # Wait for metrics to stabilize
    time.sleep(2)
    
    # Get initial metrics
    initial_processed = extract_metric('logs_processed_total', PROCESSOR_METRICS)
    initial_metrics = get_metrics()
    
    # Send messages
    latencies, actual_duration = send_messages_at_rate(messages_per_second, duration)
    
    # Wait for processing to complete
    print("  Waiting for messages to be processed...")
    time.sleep(5)
    
    # Get final metrics
    final_processed = extract_metric('logs_processed_total', PROCESSOR_METRICS)
    total_processed = final_processed - initial_processed
    throughput = total_processed / actual_duration if actual_duration > 0 else 0
    
    final_metrics = get_metrics()
    
    # Calculate latency statistics from collected latencies
    if latencies:
        publish_latency_p50 = statistics.median(latencies)
        publish_latency_p95 = statistics.quantiles(latencies, n=20)[18] if len(latencies) > 1 else latencies[0]
        publish_latency_p99 = statistics.quantiles(latencies, n=100)[98] if len(latencies) > 1 else latencies[0]
    else:
        publish_latency_p50 = final_metrics.get('publish_latency_p50', 0)
        publish_latency_p95 = final_metrics.get('publish_latency_p95', 0)
        publish_latency_p99 = final_metrics.get('publish_latency_p99', 0)
    
    # Write to CSV
    timestamp = datetime.now().isoformat()
    row = [
        timestamp,
        test_name,
        producer_count,
        messages_per_second,
        int(total_processed),
        round(actual_duration, 2),
        round(publish_latency_p50, 2),
        round(publish_latency_p95, 2),
        round(publish_latency_p99, 2),
        round(final_metrics.get('db_write_latency_p50', 0), 2),
        round(final_metrics.get('db_write_latency_p95', 0), 2),
        round(final_metrics.get('db_write_latency_p99', 0), 2),
        round(throughput, 2),
        int(final_metrics.get('stream_lag', 0))
    ]
    
    # Append to CSV
    with open(results_file, 'a', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(row)
    
    print(f"\n✓ Test complete")
    print(f"  Throughput: {throughput:.2f} logs/sec")
    print(f"  Publish latency (p95): {publish_latency_p95:.2f}ms")
    print(f"  DB write latency (p95): {final_metrics.get('db_write_latency_p95', 0):.2f}ms")
    print(f"  Stream lag: {int(final_metrics.get('stream_lag', 0))}")
    
    # Wait between tests
    time.sleep(3)


def main():
    """Run benchmark suite"""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    results_file = os.path.join(OUTPUT_DIR, f"benchmark_{timestamp}.csv")
    
    # Initialize CSV with headers
    headers = [
        'timestamp', 'test_name', 'producer_count', 'messages_per_second',
        'total_messages', 'duration_seconds', 'publish_latency_p50_ms',
        'publish_latency_p95_ms', 'publish_latency_p99_ms',
        'db_write_latency_p50_ms', 'db_write_latency_p95_ms',
        'db_write_latency_p99_ms', 'throughput_logs_per_sec', 'stream_lag_max'
    ]
    
    with open(results_file, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(headers)
    
    print("=== Logging Platform Benchmark ===")
    print(f"Results will be saved to: {results_file}\n")
    
    # Run benchmark tests
    tests = [
        ("low_load", 1, 10, 30),
        ("medium_load", 1, 50, 30),
        ("high_load", 1, 100, 30),
        ("very_high_load", 1, 200, 30),
        ("burst_load", 1, 500, 10),
    ]
    
    for test_name, producer_count, rate, duration in tests:
        run_test(test_name, producer_count, rate, duration, results_file)
    
    print(f"\n{'='*60}")
    print("=== Benchmark Complete ===")
    print(f"Results saved to: {results_file}")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    main()

