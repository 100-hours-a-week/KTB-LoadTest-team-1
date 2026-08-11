package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * rate limit 전용 RedissonClient 빈. 세션용({@link SessionRedissonConfig})과 같은 물리 Redis
 * 인스턴스(redis.session.host/port/password)를 쓰되, DB index를 3으로 분리해서 세션(2) 및
 * Socket.IO 쪽({@link RedissonConfig}: 0, 1)과 안 섞이게 한다 — 물리 인스턴스를 합쳐 쓰든 분리해서
 * 쓰든 이 배정으로 항상 안전하다.
 * {@code session.store.type}과 독립적으로 {@code ratelimit.store.type=redis}만으로 켤 수 있다.
 */
@Configuration
@ConditionalOnProperty(name = "ratelimit.store.type", havingValue = "redis")
public class RateLimitRedissonConfig {

    @Value("${redis.session.host}")
    private String host;

    @Value("${redis.session.port}")
    private int port;

    @Value("${redis.session.password:}")
    private String password;

    @Bean(name = "rateLimitRedissonClient", destroyMethod = "shutdown")
    @Qualifier("rateLimitRedissonClient")
    public RedissonClient rateLimitRedissonClient() {
        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(3);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }
        config.setCodec(new JsonJacksonCodec());
        return Redisson.create(config);
    }
}
