package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

/**
 * Redis 기반 SessionStore 구현체. 유저당 세션 하나(단일 세션 정책)라 그냥 {@code userId} 키의
 * bucket으로 저장하고, TTL은 Redis 네이티브 EXPIRE로 건다(Mongo TTL 인덱스처럼 백그라운드 스캔을
 * 기다릴 필요 없이 만료 즉시 키가 사라진다).
 *
 * <p>{@code Session.expiresAt}이 {@code Instant}라서 Redisson 기본 JsonJacksonCodec의 ObjectMapper
 * (JavaTimeModule 미등록)로는 직렬화가 안 된다 — 직접 등록한 ObjectMapper로 코덱을 만든다.
 */
@Component
@ConditionalOnProperty(name = "session.store.type", havingValue = "redis")
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "session:userid:";
    private static final long SESSION_TTL_SECONDS = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();

    private final RedissonClient redissonClient;
    private final Codec sessionCodec;

    public SessionRedisStore(@Qualifier("sessionRedissonClient") RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.sessionCodec = new TypedJsonJacksonCodec(Session.class, objectMapper);
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        Session session = redissonClient.<Session>getBucket(buildKey(userId), sessionCodec).get();
        return Optional.ofNullable(session);
    }

    @Override
    public Session save(Session session) {
        redissonClient.<Session>getBucket(buildKey(session.getUserId()), sessionCodec)
                .set(session, Duration.ofSeconds(SESSION_TTL_SECONDS));
        return session;
    }

    @Override
    public void delete(String userId, String sessionId) {
        findByUserId(userId).ifPresent(existing -> {
            if (sessionId.equals(existing.getSessionId())) {
                deleteAll(userId);
            }
        });
    }

    @Override
    public void deleteAll(String userId) {
        redissonClient.getBucket(buildKey(userId)).delete();
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }
}
