#!/bin/bash

# Benchmarking Script for Logging Platform
# Measures throughput and latency with scaled producers

set -e

# Configuration
PRODUCER_URL="http://localhost:8080/api/logs"
PROCESSOR_METRICS="http://localhost:8081/actuator/prometheus"
PRODUCER_METRICS="http://localhost:8080/actuator/prometheus"
OUTPUT_DIR="benchmarks"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_FILE="${OUTPUT_DIR}/benchmark_${TIMESTAMP}.csv"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== Logging Platform Benchmark ==="
echo ""

# Initialize CSV file with headers
echo "timestamp,test_name,producer_count,messages_per_second,total_messages,duration_seconds,publish_latency_p50_ms,publish_latency_p95_ms,publish_latency_p99_ms,db_write_latency_p50_ms,db_write_latency_p95_ms,db_write_latency_p99_ms,throughput_logs_per_sec,stream_lag_max" > "$RESULTS_FILE"

# Function to extract metric value from Prometheus format
extract_metric() {
    local metric_name=$1
    local url=$2
    curl -s "$url" | grep "^${metric_name}" | head -1 | awk '{print $2}' | sed 's/^"//;s/"$//'
}

# Function to extract histogram quantile
extract_quantile() {
    local metric_base=$1
    local quantile=$2
    local url=$3
    local value=$(curl -s "$url" | grep "${metric_base}_bucket" | awk '{print $2}' | head -1 | sed 's/^"//;s/"$//')
    echo "$value"
}

# Function to get current metrics
get_metrics() {
    local metrics_url=$1
    
    # Get publish latency (from producer)
    local pub_p50=$(extract_metric "logs_publish_latency_seconds{quantile=\"0.5\"}" "$PRODUCER_METRICS" || echo "0")
    local pub_p95=$(extract_metric "logs_publish_latency_seconds{quantile=\"0.95\"}" "$PRODUCER_METRICS" || echo "0")
    local pub_p99=$(extract_metric "logs_publish_latency_seconds{quantile=\"0.99\"}" "$PRODUCER_METRICS" || echo "0")
    
    # Get DB write latency (from processor)
    local db_p50=$(extract_metric "logs_db_write_latency_seconds{quantile=\"0.5\"}" "$PROCESSOR_METRICS" || echo "0")
    local db_p95=$(extract_metric "logs_db_write_latency_seconds{quantile=\"0.95\"}" "$PROCESSOR_METRICS" || echo "0")
    local db_p99=$(extract_metric "logs_db_write_latency_seconds{quantile=\"0.99\"}" "$PROCESSOR_METRICS" || echo "0")
    
    # Get throughput (processed logs per second)
    local processed_total=$(extract_metric "logs_processed_total" "$PROCESSOR_METRICS" || echo "0")
    
    # Get stream lag
    local stream_lag=$(extract_metric "logs_stream_lag" "$PROCESSOR_METRICS" || echo "0")
    
    echo "$pub_p50,$pub_p95,$pub_p99,$db_p50,$db_p95,$db_p99,$processed_total,$stream_lag"
}

# Function to send messages at a given rate
send_messages() {
    local rate=$1  # messages per second
    local duration=$2  # seconds
    local total_messages=$((rate * duration))
    local delay=$(echo "scale=6; 1.0 / $rate" | bc)
    
    echo "  Sending $total_messages messages at $rate msg/s..."
    
    local start_time=$(date +%s%N)
    local sent=0
    
    for i in $(seq 1 $total_messages); do
        curl -s -X POST "$PRODUCER_URL" \
            -H "Content-Type: application/json" \
            -d "{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)\",\"app\":\"benchmark-app\",\"level\":\"INFO\",\"msg\":\"Benchmark message $i\",\"fields\":{\"testId\":$i,\"rate\":$rate}}" \
            > /dev/null
        
        sent=$((sent + 1))
        
        # Rate limiting
        if [ $sent -lt $total_messages ]; then
            sleep $delay
        fi
    done
    
    local end_time=$(date +%s%N)
    local actual_duration=$(echo "scale=2; ($end_time - $start_time) / 1000000000" | bc)
    
    echo "$actual_duration"
}

# Function to run a benchmark test
run_test() {
    local test_name=$1
    local producer_count=$2
    local messages_per_second=$3
    local duration=$4
    
    echo -e "${YELLOW}Running test: $test_name${NC}"
    echo "  Producers: $producer_count (simulated)"
    echo "  Rate: $messages_per_second msg/s"
    echo "  Duration: $duration seconds"
    
    # Reset metrics (wait a bit for metrics to stabilize)
    sleep 2
    
    # Get initial processed count
    local initial_processed=$(extract_metric "logs_processed_total" "$PROCESSOR_METRICS" || echo "0")
    
    # Send messages
    local actual_duration=$(send_messages $messages_per_second $duration)
    
    # Wait for processing to complete
    echo "  Waiting for messages to be processed..."
    sleep 5
    
    # Get final metrics
    local final_processed=$(extract_metric "logs_processed_total" "$PROCESSOR_METRICS" || echo "0")
    local total_processed=$(echo "$final_processed - $initial_processed" | bc)
    local throughput=$(echo "scale=2; $total_processed / $actual_duration" | bc)
    
    # Get latency metrics
    local metrics=$(get_metrics "$PROCESSOR_METRICS")
    local pub_p50=$(echo "$metrics" | cut -d',' -f1)
    local pub_p95=$(echo "$metrics" | cut -d',' -f2)
    local pub_p99=$(echo "$metrics" | cut -d',' -f3)
    local db_p50=$(echo "$metrics" | cut -d',' -f4)
    local db_p95=$(echo "$metrics" | cut -d',' -f5)
    local db_p99=$(echo "$metrics" | cut -d',' -f6)
    local max_lag=$(echo "$metrics" | cut -d',' -f8)
    
    # Convert latencies to milliseconds
    local pub_p50_ms=$(echo "scale=2; $pub_p50 * 1000" | bc)
    local pub_p95_ms=$(echo "scale=2; $pub_p95 * 1000" | bc)
    local pub_p99_ms=$(echo "scale=2; $pub_p99 * 1000" | bc)
    local db_p50_ms=$(echo "scale=2; $db_p50 * 1000" | bc)
    local db_p95_ms=$(echo "scale=2; $db_p95 * 1000" | bc)
    local db_p99_ms=$(echo "scale=2; $db_p99 * 1000" | bc)
    
    # Write to CSV
    local timestamp=$(date +%Y-%m-%dT%H:%M:%S)
    echo "$timestamp,$test_name,$producer_count,$messages_per_second,$total_processed,$actual_duration,$pub_p50_ms,$pub_p95_ms,$pub_p99_ms,$db_p50_ms,$db_p95_ms,$db_p99_ms,$throughput,$max_lag" >> "$RESULTS_FILE"
    
    echo -e "${GREEN}✓ Test complete${NC}"
    echo "  Throughput: $throughput logs/sec"
    echo "  Publish latency (p95): ${pub_p95_ms}ms"
    echo "  DB write latency (p95): ${db_p95_ms}ms"
    echo ""
    
    # Wait between tests
    sleep 3
}

# Check if bc is installed
if ! command -v bc &> /dev/null; then
    echo "Error: 'bc' command not found. Please install it."
    exit 1
fi

# Run benchmark tests
echo "Starting benchmark tests..."
echo ""

# Test 1: Low load
run_test "low_load" 1 10 30

# Test 2: Medium load
run_test "medium_load" 1 50 30

# Test 3: High load
run_test "high_load" 1 100 30

# Test 4: Very high load
run_test "very_high_load" 1 200 30

# Test 5: Burst load
run_test "burst_load" 1 500 10

echo "=== Benchmark Complete ==="
echo "Results saved to: $RESULTS_FILE"
echo ""

