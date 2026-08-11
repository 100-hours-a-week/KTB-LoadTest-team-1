package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Socket.IO 전용 Redis 인스턴스에 대한 RedissonClient 빈 2개를 등록한다.
 *
 * <p>netty-socketio 자체 스토어(room/브로드캐스트 라우팅)와 우리 {@code ChatDataStore}가 같은 물리
 * Redis(같은 host:port)를 쓰되, 논리 DB index를 분리한다(0=socketio 자체 스토어, 1=ChatDataStore).
 * 이렇게 하면 {@code RedisChatDataStore.size()}가 자기 DB의 키만 세면 되고, netty-socketio가 내부적으로
 * 쓰는 키와 섞이지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "socketio.store.type", havingValue = "redis")
public class RedissonConfig {

    @Value("${redis.socketio.host}")
    private String host;

    @Value("${redis.socketio.port}")
    private int port;

    @Value("${redis.socketio.password:}")
    private String password;

    @Bean(name = "socketioStoreRedissonClient", destroyMethod = "shutdown")
    @Qualifier("socketioStoreRedissonClient")
    public RedissonClient socketioStoreRedissonClient() {
        Config config = new Config();
        configureSingleServer(config.useSingleServer().setDatabase(0));
        return Redisson.create(config);
    }

    @Bean(name = "chatDataStoreRedissonClient", destroyMethod = "shutdown")
    @Qualifier("chatDataStoreRedissonClient")
    public RedissonClient chatDataStoreRedissonClient() {
        Config config = new Config();
        configureSingleServer(config.useSingleServer().setDatabase(1));
        config.setCodec(new JsonJacksonCodec());
        return Redisson.create(config);
    }

    private void configureSingleServer(SingleServerConfig serverConfig) {
        serverConfig.setAddress("redis://" + host + ":" + port);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }
    }
}
