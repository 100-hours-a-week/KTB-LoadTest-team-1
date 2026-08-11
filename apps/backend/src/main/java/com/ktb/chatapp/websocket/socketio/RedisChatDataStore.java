package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.TypedJsonJacksonCodec;

/**
 * Redis 기반 ChatDataStore 구현체. 인스턴스가 여러 대여도 ConnectedUsers/UserRooms 데이터를 공유한다
 * (LocalChatDataStore는 JVM-local이라 이 데이터가 인스턴스마다 따로 논다).
 *
 * <p>이 클래스에 주입되는 RedissonClient는 ChatDataStore 전용 DB index로 분리돼 있어({@link
 * RedissonConfig#chatDataStoreRedissonClient()}), {@link #size()}가 netty-socketio 자체 스토어의
 * 키와 섞이지 않고 우리 데이터만 정확히 셀 수 있다.
 *
 * <p>키마다 저장 타입이 다르므로(SocketUser, Set&lt;String&gt;) 클라이언트 전역 코덱 하나로는 읽을 때
 * 어떤 타입으로 역직렬화할지 알 수 없다 — {@code JsonJacksonCodec}의 기본 ObjectMapper는 이걸 다형
 * 타입 힌트({@code @class} 프로퍼티)로 해결하려 하는데, 쓸 때 그 힌트를 안 남겨서 읽을 때
 * {@code InvalidTypeIdException}이 난다. 매 호출마다 알고 있는 구체 타입으로 {@link
 * TypedJsonJacksonCodec}을 만들어 넘겨서, 다형 타입 힌트 없이 타입을 명시적으로 고정한다.
 */
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;

    public RedisChatDataStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = redissonClient.getBucket(key, codecFor(type)).get();
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        redissonClient.getBucket(key, codecFor(value.getClass())).set(value);
    }

    @Override
    public void delete(String key) {
        redissonClient.getBucket(key).delete();
    }

    @Override
    public int size() {
        return (int) redissonClient.getKeys().count();
    }

    private Codec codecFor(Class<?> type) {
        return new TypedJsonJacksonCodec(type);
    }
}
