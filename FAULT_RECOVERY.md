# Fault Recovery Testing Guide

## Overview

The logging platform implements fault recovery using Redis Streams consumer groups. When the processor fails or is restarted, pending messages are automatically recovered and processed without data loss.

## How It Works

1. **Consumer Groups**: Messages are consumed using Redis Streams consumer groups (`log-processors`)
2. **Ack-on-Write**: Messages are only acknowledged after successful database persistence
3. **Pending Message Recovery**: On restart, the processor automatically processes all unacknowledged messages
4. **No Message Loss**: Unacknowledged messages remain in Redis and are retried

## Testing Fault Recovery

### Prerequisites

- Docker and Docker Compose installed
- All services running: `docker compose up -d`
- Processor service accessible

### Manual Test Steps

1. **Start the system**:
   ```bash
   docker compose up -d
   ```

2. **Send test messages**:
   ```bash
   for i in {1..50}; do
     curl -X POST http://localhost:8080/api/logs \
       -H "Content-Type: application/json" \
       -d "{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)\",\"app\":\"test\",\"level\":\"INFO\",\"msg\":\"Test $i\"}"
   done
   ```

3. **Kill the processor**:
   ```bash
   docker kill processor
   ```

4. **Send more messages while processor is down**:
   ```bash
   for i in {1..20}; do
     curl -X POST http://localhost:8080/api/logs \
       -H "Content-Type: application/json" \
       -d "{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)\",\"app\":\"test\",\"level\":\"INFO\",\"msg\":\"During downtime $i\"}"
   done
   ```

5. **Check pending messages in Redis**:
   ```bash
   docker exec redis redis-cli XPENDING logs:stream log-processors
   ```

6. **Restart the processor**:
   ```bash
   docker start processor
   ```

7. **Monitor recovery**:
   - Check processor logs: `docker logs -f processor`
   - Watch for "Recovery started" and "Recovery complete" messages
   - Verify pending messages decrease to zero

8. **Verify no message loss**:
   ```bash
   docker exec redis redis-cli XPENDING logs:stream log-processors
   # Should show 0 pending messages
   ```

### Automated Test Script

Run the automated test script:

```bash
chmod +x test-fault-recovery.sh
./test-fault-recovery.sh
```

The script will:
- Send test messages
- Kill the processor
- Send messages during downtime
- Restart the processor
- Measure recovery time
- Verify no message loss

## Expected Behavior

1. **During Normal Operation**:
   - Messages are processed immediately
   - Stream lag remains low (< 10 messages typically)

2. **After Processor Failure**:
   - Messages accumulate in Redis Stream
   - Pending message count increases
   - No messages are lost

3. **During Recovery**:
   - Processor detects pending messages on startup
   - Processes all pending messages in batches
   - Acknowledges messages only after successful DB write
   - Stream lag decreases to zero

4. **Recovery Metrics**:
   - `logs.recovery.count`: Number of recovery operations
   - `logs.recovery.time`: Time taken to recover all pending messages
   - `logs.stream.lag`: Current pending message count

## Monitoring Recovery

### Grafana Dashboard

The Grafana dashboard shows:
- **Stream Lag**: Monitor pending messages in real-time
- **Logs Processed**: Verify messages are being processed during recovery

### Metrics Endpoint

Check recovery metrics:
```bash
curl http://localhost:8081/actuator/prometheus | grep logs.recovery
```

## Recovery Time

Recovery time depends on:
- Number of pending messages
- Database write performance
- Batch size (currently 50 messages per batch)

Expected recovery times:
- 100 messages: ~2-5 seconds
- 1000 messages: ~20-50 seconds
- 10000 messages: ~3-10 minutes

## Troubleshooting

### Messages Not Recovering

1. Check consumer group exists:
   ```bash
   docker exec redis redis-cli XINFO GROUPS logs:stream
   ```

2. Check pending messages:
   ```bash
   docker exec redis redis-cli XPENDING logs:stream log-processors
   ```

3. Check processor logs:
   ```bash
   docker logs processor
   ```

### High Recovery Time

- Increase batch size in `StreamConsumerService.BATCH_SIZE`
- Optimize database indexes
- Check database connection pool settings

## Best Practices

1. **Monitor Stream Lag**: Set up alerts for high stream lag (>100 messages)
2. **Regular Health Checks**: Monitor processor health via Actuator endpoints
3. **Graceful Shutdown**: Allow processor to finish current batch before shutdown
4. **Database Performance**: Ensure database can handle recovery load

