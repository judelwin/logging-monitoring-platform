#!/bin/bash

# Fault Recovery Test Script
# Tests processor recovery after failure: verifies pending message processing and measures recovery time

set -e

echo "=== Fault Recovery Test ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
PRODUCER_URL="http://localhost:8080/api/logs"
PROCESSOR_CONTAINER="processor"
REDIS_CONTAINER="redis"
LOG_COUNT=100

echo "Step 1: Verify services are running..."
if ! docker ps | grep -q "$PROCESSOR_CONTAINER"; then
    echo -e "${RED}Error: Processor container not running${NC}"
    exit 1
fi
if ! docker ps | grep -q "$REDIS_CONTAINER"; then
    echo -e "${RED}Error: Redis container not running${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Services running${NC}"
echo ""

echo "Step 2: Send $LOG_COUNT log messages to producer..."
for i in $(seq 1 $LOG_COUNT); do
    curl -s -X POST "$PRODUCER_URL" \
        -H "Content-Type: application/json" \
        -d "{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)\",\"app\":\"test-app\",\"level\":\"INFO\",\"msg\":\"Test message $i\",\"fields\":{\"testId\":$i}}" \
        > /dev/null
    if [ $((i % 10)) -eq 0 ]; then
        echo "  Sent $i messages..."
    fi
done
echo -e "${GREEN}✓ Sent $LOG_COUNT messages${NC}"
echo ""

echo "Step 3: Wait for initial processing (5 seconds)..."
sleep 5

echo "Step 4: Check pending messages before killing processor..."
PENDING_BEFORE=$(docker exec $REDIS_CONTAINER redis-cli XPENDING logs:stream log-processors | head -1 | awk '{print $1}')
echo "  Pending messages: $PENDING_BEFORE"
echo ""

echo "Step 5: ${YELLOW}Killing processor container...${NC}"
docker kill $PROCESSOR_CONTAINER > /dev/null
echo -e "${GREEN}✓ Processor killed${NC}"
echo ""

echo "Step 6: Send additional messages while processor is down..."
for i in $(seq 1 20); do
    curl -s -X POST "$PRODUCER_URL" \
        -H "Content-Type: application/json" \
        -d "{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)\",\"app\":\"test-app\",\"level\":\"INFO\",\"msg\":\"Message during downtime $i\",\"fields\":{\"testId\":$((LOG_COUNT + i))}}" \
        > /dev/null
done
echo -e "${GREEN}✓ Sent 20 messages during downtime${NC}"
echo ""

echo "Step 7: Check pending messages while processor is down..."
sleep 2
PENDING_DURING=$(docker exec $REDIS_CONTAINER redis-cli XPENDING logs:stream log-processors | head -1 | awk '{print $1}')
echo "  Pending messages: $PENDING_DURING"
echo ""

echo "Step 8: ${YELLOW}Restarting processor...${NC}"
RECOVERY_START=$(date +%s%N)
docker start $PROCESSOR_CONTAINER > /dev/null
echo -e "${GREEN}✓ Processor restarted${NC}"
echo ""

echo "Step 9: Waiting for recovery (up to 60 seconds)..."
MAX_WAIT=60
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    PENDING_NOW=$(docker exec $REDIS_CONTAINER redis-cli XPENDING logs:stream log-processors 2>/dev/null | head -1 | awk '{print $1}' || echo "0")
    
    if [ "$PENDING_NOW" = "0" ] || [ -z "$PENDING_NOW" ]; then
        RECOVERY_END=$(date +%s%N)
        RECOVERY_TIME_MS=$(( (RECOVERY_END - RECOVERY_START) / 1000000 ))
        echo -e "${GREEN}✓ Recovery complete in ${RECOVERY_TIME_MS}ms${NC}"
        break
    fi
    
    echo "  Waiting... ($ELAPSED seconds, pending: $PENDING_NOW)"
done
echo ""

if [ $ELAPSED -ge $MAX_WAIT ]; then
    echo -e "${RED}✗ Recovery timeout after $MAX_WAIT seconds${NC}"
    exit 1
fi

echo "Step 10: Verify no message loss..."
FINAL_PENDING=$(docker exec $REDIS_CONTAINER redis-cli XPENDING logs:stream log-processors 2>/dev/null | head -1 | awk '{print $1}' || echo "0")
if [ "$FINAL_PENDING" != "0" ] && [ -n "$FINAL_PENDING" ]; then
    echo -e "${RED}✗ Still have pending messages: $FINAL_PENDING${NC}"
    exit 1
fi
echo -e "${GREEN}✓ No pending messages - all messages processed${NC}"
echo ""

echo "=== Test Results ==="
echo "Messages sent: $((LOG_COUNT + 20))"
echo "Pending before kill: $PENDING_BEFORE"
echo "Pending during downtime: $PENDING_DURING"
echo "Recovery time: ${RECOVERY_TIME_MS}ms"
echo ""
echo -e "${GREEN}✓ Fault recovery test PASSED${NC}"

