package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.corundumstudio.socketio.store.StoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${socketio.server.origin:*}")
    private String origin;

    // TCP accept 큐 길이. 이전엔 10이라 연결이 짧은 시간에 몰리면(부하테스트 arrival burst 등)
    // 커널 SYN 큐가 금방 차 연결 자체가 드롭될 수 있었다.
    private static final int ACCEPT_BACKLOG = 512;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(
            AuthTokenListener authTokenListener, MeterRegistry meterRegistry, StoreFactory storeFactory) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);
        
        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(false);
        socketConfig.setAcceptBackLog(ACCEPT_BACKLOG);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin(origin);

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(storeFactory);

        log.info("Socket.IO server configured on {}:{} with {} boss threads and {} worker threads",
                 host, port, config.getBossThreads(), config.getWorkerThreads());
        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addEventInterceptor((client, name, data, ack) -> {
            // 이벤트 발생 빈도 수집
            Counter.builder("socketio.events.total")
                .description("Total Socket.IO events received")
                .tag("event_type", name)
                .register(meterRegistry)
                .increment();
        });
        
        return socketIOServer;
    }
    
    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
    
    /**
     * netty-socketio 자체 room/브로드캐스트 스토어. {@code socketio.store.type}으로 전환한다 —
     * {@code local}(기본, 단일 노드 전용) 또는 {@code redis}(멀티 인스턴스, RedissonConfig 참고).
     */
    @Bean
    @ConditionalOnProperty(name = "socketio.store.type", havingValue = "local", matchIfMissing = true)
    public StoreFactory memoryStoreFactory() {
        return new MemoryStoreFactory();
    }

    @Bean
    @ConditionalOnProperty(name = "socketio.store.type", havingValue = "redis")
    public StoreFactory redissonStoreFactory(
            @Qualifier("socketioStoreRedissonClient") RedissonClient redissonClient) {
        return new RedissonStoreFactory(redissonClient);
    }

    // 단일 노드 환경 전용 — JVM-local이라 인스턴스가 여러 대면 ConnectedUsers/UserRooms 데이터가 안 섞인다.
    @Bean
    @ConditionalOnProperty(name = "socketio.store.type", havingValue = "local", matchIfMissing = true)
    public ChatDataStore localChatDataStore() {
        return new LocalChatDataStore();
    }

    // 멀티 인스턴스용 — 인스턴스 간 ConnectedUsers/UserRooms 데이터를 공유한다.
    @Bean
    @ConditionalOnProperty(name = "socketio.store.type", havingValue = "redis")
    public ChatDataStore redisChatDataStore(
            @Qualifier("chatDataStoreRedissonClient") RedissonClient redissonClient) {
        return new RedisChatDataStore(redissonClient);
    }
}
