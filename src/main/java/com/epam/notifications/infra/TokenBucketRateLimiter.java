package com.epam.notifications.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TokenBucketRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local nowMs = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refillPerSec = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])

            local tokens = tonumber(redis.call('HGET', key, 'tokens'))
            local lastRefillAt = tonumber(redis.call('HGET', key, 'lastRefillAt'))

            if tokens == nil then
              tokens = capacity
            end

            if lastRefillAt == nil then
              lastRefillAt = nowMs
            end

            local elapsedSeconds = (nowMs - lastRefillAt) / 1000.0
            tokens = math.min(capacity, tokens + (elapsedSeconds * refillPerSec))

            local allowed = 0
            local retryAfter = 0
            if tokens >= cost then
              allowed = 1
              tokens = tokens - cost
            else
              local deficit = cost - tokens
              retryAfter = math.ceil(deficit / refillPerSec)
            end

            redis.call('HSET', key, 'tokens', tokens, 'lastRefillAt', nowMs)
            redis.call('EXPIRE', key, 3600)
            return {allowed, retryAfter}
            """;

    private final StringRedisTemplate redisTemplate;
    private final double capacity;
    private final double refillPerSecond;
    private final DefaultRedisScript<List> rateLimiterScript;
    private final String rateLimitPrefix;
    private final ConcurrentMap<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
            @Value("${notification.rate-limit.capacity:10}") double capacity,
            @Value("${notification.rate-limit.refill-per-second:5}") double refillPerSecond,
            @Value("${notification.queue.key-prefix:notification}") String keyPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.rateLimitPrefix = keyPrefix + ":ratelimit:";
        this.rateLimiterScript = new DefaultRedisScript<>();
        this.rateLimiterScript.setScriptText(TOKEN_BUCKET_SCRIPT);
        this.rateLimiterScript.setResultType(List.class);
    }

    public Decision consume(String key, double cost) {
      long nowMs = System.currentTimeMillis();
      try {
        List<?> result = redisTemplate.execute(
            rateLimiterScript,
            List.of(rateLimitPrefix + key),
            String.valueOf(nowMs),
            String.valueOf(capacity),
            String.valueOf(refillPerSecond),
            String.valueOf(cost)
        );

        if (result == null || result.size() < 2) {
          return new Decision(false, 1);
        }

        boolean allowed = toLong(result.get(0)) == 1;
        long retryAfter = Math.max(0, toLong(result.get(1)));
        return new Decision(allowed, retryAfter);
      } catch (RuntimeException exception) {
        log.warn("Redis unavailable for rate limiting, using local fallback: {}", exception.getMessage());
        return consumeLocally(key, nowMs, cost);
      }
    }

    private Decision consumeLocally(String key, long nowMs, double cost) {
      String localKey = rateLimitPrefix + key;
      LocalBucket bucket = localBuckets.computeIfAbsent(localKey, ignored -> new LocalBucket(capacity, nowMs));
      synchronized (bucket) {
        double elapsedSeconds = (nowMs - bucket.lastRefillAtMs) / 1000.0;
        bucket.tokens = Math.min(capacity, bucket.tokens + (elapsedSeconds * refillPerSecond));
        bucket.lastRefillAtMs = nowMs;

        if (bucket.tokens >= cost) {
          bucket.tokens -= cost;
          return new Decision(true, 0);
        }

        double deficit = cost - bucket.tokens;
        long retryAfter = (long) Math.ceil(deficit / refillPerSecond);
        return new Decision(false, Math.max(1, retryAfter));
      }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class LocalBucket {
      private double tokens;
      private long lastRefillAtMs;

      private LocalBucket(double tokens, long lastRefillAtMs) {
        this.tokens = tokens;
        this.lastRefillAtMs = lastRefillAtMs;
      }
    }
}
