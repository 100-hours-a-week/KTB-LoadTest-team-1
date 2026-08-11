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
 * 세션 전용 Redis 인스턴스에 대한 RedissonClient 빈. Socket.IO용 Redis({@link RedissonConfig})와는
 * 별개 물리 인스턴스다 — 세션은 요청 경로에서 매번 치는 단순 KV/TTL 패턴이라, Socket.IO의 pub/sub
 * 트래픽(부하테스트로 몰릴 수 있음)과 서로 영향을 주지 않도록 인스턴스를 분리했다.
 *
 * <p>DB index 0을 세션이 쓰고, 1은 추후 rate-limit을 이 인스턴스로 옮길 때를 위해 비워둔다.
 */
@Configuration
@ConditionalOnProperty(name = "session.store.type", havingValue = "redis")
public class SessionRedissonConfig {

    @Value("${redis.session.host}")
    private String host;

    @Value("${redis.session.port}")
    private int port;

    @Value("${redis.session.password:}")
    private String password;

    @Bean(name = "sessionRedissonClient", destroyMethod = "shutdown")
    @Qualifier("sessionRedissonClient")
    public RedissonClient sessionRedissonClient() {
        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(0);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }
        config.setCodec(new JsonJacksonCodec());
        return Redisson.create(config);
    }
}
