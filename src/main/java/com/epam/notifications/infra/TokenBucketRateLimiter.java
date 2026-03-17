package com.epam.notifications.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenBucketRateLimiter {

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
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
