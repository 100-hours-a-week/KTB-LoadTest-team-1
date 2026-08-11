package com.ktb.chatapp.service.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.RateLimitCheckResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 RateLimitStore 구현체.
 *
 * <p>{@link #tryAtomicCheckAndIncrement}가 Lua 스크립트로 INCR + (최초 요청이면) EXPIRE + TTL 조회를
 * 한 번의 원자적 명령으로 처리한다 — {@code RateLimitService}의 기존 find-check-save 경로에 있던
 * 동시 요청 간 경쟁 조건(여러 요청이 같은 카운트를 읽고 각자 증가시켜 저장 → 한도보다 더 많이 통과)이
 * 없다. Mongo는 이 메서드를 오버라이드하지 않으므로(기본 구현이 empty 반환) 예전 방식 그대로 동작한다.
 *
 * <p>{@link #findByClientId}/{@link #save}(기존 bucket 기반 저장)는 별도 key 네임스페이스에
 * 남겨뒀다 — 이 저장소를 쓰는 동안은 원자적 경로가 항상 우선이라 실제로는 호출되지 않지만, 인터페이스
 * 계약은 그대로 완전하게 만족시킨다.
 */
@Component
@ConditionalOnProperty(name = "ratelimit.store.type", havingValue = "redis")
public class RateLimitRedisStore implements RateLimitStore {

    private static final String BUCKET_KEY_PREFIX = "ratelimit:clientid:";
    private static final String COUNTER_KEY_PREFIX = "ratelimit:counter:";

    // KEYS[1] = 카운터 키, ARGV[1] = window(초). 최초 요청(count==1)일 때만 TTL을 새로 건다 —
    // 그래야 윈도우 중간에 들어온 요청이 TTL을 계속 늘려서 윈도우가 안 끝나는 문제가 없다.
    private static final String CHECK_AND_INCREMENT_SCRIPT =
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "local ttl = redis.call('TTL', KEYS[1]) " +
            "return {current, ttl}";

    private final RedissonClient redissonClient;
    private final Codec rateLimitCodec;

    public RateLimitRedisStore(@Qualifier("rateLimitRedissonClient") RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.rateLimitCodec = new TypedJsonJacksonCodec(RateLimit.class, objectMapper);
    }

    @Override
    public Optional<RateLimitCheckResult> tryAtomicCheckAndIncrement(
            String clientId, int maxRequests, long windowSeconds) {
        List<Long> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                CHECK_AND_INCREMENT_SCRIPT,
                RScript.ReturnType.LIST,
                Collections.singletonList(counterKey(clientId)),
                windowSeconds);

        long count = result.get(0);
        long ttl = Math.max(1L, result.get(1));
        long resetEpochSeconds = Instant.now().getEpochSecond() + ttl;

        if (count > maxRequests) {
            return Optional.of(RateLimitCheckResult.rejected(maxRequests, windowSeconds, resetEpochSeconds, ttl));
        }

        int remaining = (int) Math.max(0, maxRequests - count);
        return Optional.of(
                RateLimitCheckResult.allowed(maxRequests, remaining, windowSeconds, resetEpochSeconds, ttl));
    }

    @Override
    public Optional<RateLimit> findByClientId(String clientId) {
        RateLimit value = redissonClient.<RateLimit>getBucket(bucketKey(clientId), rateLimitCodec).get();
        return Optional.ofNullable(value);
    }

    @Override
    public RateLimit save(RateLimit rateLimit) {
        long ttlSeconds = Math.max(1L, Duration.between(Instant.now(), rateLimit.getExpiresAt()).getSeconds());
        redissonClient.<RateLimit>getBucket(bucketKey(rateLimit.getClientId()), rateLimitCodec)
                .set(rateLimit, Duration.ofSeconds(ttlSeconds));
        return rateLimit;
    }

    private String bucketKey(String clientId) {
        return BUCKET_KEY_PREFIX + clientId;
    }

    private String counterKey(String clientId) {
        return COUNTER_KEY_PREFIX + clientId;
    }
}
