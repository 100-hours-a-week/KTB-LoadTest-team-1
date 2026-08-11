# 16. Socket.IO가 멀티 인스턴스에서 정합성이 깨지는 구조였다

> **한줄 요약**: `MemoryStoreFactory`/`LocalChatDataStore`는 JVM-local이라, 백엔드를 여러 인스턴스로
> 띄우면 룸 브로드캐스트·중복 로그인 감지가 인스턴스 경계를 못 넘었다. Redisson 기반 스토어로
> 교체해서 여러 인스턴스가 진짜로 하나의 채팅 서버처럼 동작하게 만들었다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 완료(옵트인, `socketio.store.type=redis`) | 멀티 인스턴스 수평 확장(Socket.IO 전체) | Critical(확장 시) |

## 문제 (원래 상태)

`config/SocketIOConfig.java`가 netty-socketio의 기본 `MemoryStoreFactory`를 썼고, 룸 참여자
목록/연결 상태(`ConnectedUsers`, `UserRooms`)는 `LocalChatDataStore`라는 자체 in-memory 구현체가
들고 있었다. 둘 다 JVM 프로세스 하나 안에서만 유효한 상태다.

백엔드를 인스턴스 1대로만 운영하는 동안은 문제가 없지만, ALB 뒤에 인스턴스를 2대 이상 띄우는
순간 다음이 깨진다:

- **룸 브로드캐스트**: A 인스턴스에 붙은 소켓이 보낸 메시지가 B 인스턴스에 붙은 같은 방의
  다른 소켓들에게 전달되지 않는다(각 인스턴스가 자기 프로세스 메모리의 room 멤버십만 안다).
- **중복 로그인 감지**(`ConnectionLoginHandler.notifyDuplicateLogin`): 기존 코드가
  `socketIOServer.getClient(UUID.fromString(sessionId))`로 클라이언트 객체를 직접 찾는
  방식이었는데, 이건 **그 클라이언트가 지금 이 요청을 처리 중인 인스턴스에 붙어 있을 때만
  동작**한다. 새 로그인을 처리한 인스턴스와, 기존 세션의 소켓이 붙어있는 인스턴스가 다르면
  `getClient()`가 `null`을 반환해서 중복 로그인 알림 자체가 조용히 안 나간다. 코드에 실제로
  `TODO 멀티 클러스터에서 동작 안함` 주석이 달려 있었다.

즉 인스턴스를 늘리는 순간 채팅 자체가 부분적으로만 동작하는(같은 인스턴스에 붙은 사람들끼리만
대화가 되는) 상태가 될 수 있었다.

## 조치 ✅

`socketio.store.type` 프로퍼티로 `local`(기본, JVM-local) ↔ `redis` 전환 가능하게 만들었다.

- `config/RedissonConfig.java` — 두 개의 `RedissonClient` 빈 분리:
  - `socketioStoreRedissonClient`(DB 0) — netty-socketio 자체 room/브로드캐스트용
  - `chatDataStoreRedissonClient`(DB 1) — `ConnectedUsers`/`UserRooms`용
- `config/SocketIOConfig.java` — `StoreFactory`/`ChatDataStore` 빈을 `local`/`redis`로 조건부
  분기(`redis`일 때 `RedissonStoreFactory`).
- `websocket/socketio/RedisChatDataStore.java`(신규) — `ChatDataStore` 인터페이스를 Redis
  기반으로 재구현.
- `websocket/socketio/handler/ConnectionLoginHandler.java` — `notifyDuplicateLogin`을
  `socketIOServer.getClient(UUID)`(JVM-local 전용) 대신 **room 브로드캐스트**로 재작성:
  `socketIOServer.getRoomOperations("socket:" + socketId).sendEvent(...)`. 이를 위해 `onConnect`
  에서 각 소켓이 자기 자신만의 room(`socket:{sessionId}`)에 join하고, `onDisconnect`에서
  leave하도록 추가했다 — Redisson store가 room 멤버십을 Redis에 공유하기 때문에, 어느 인스턴스가
  이벤트를 보내든 실제로 그 소켓이 붙어있는 인스턴스까지 전달된다.

## 검증

실제 Redis에 대해:
- DB 0/DB 1에 각각 Socket.IO room 데이터, `ConnectedUsers`/`UserRooms` 데이터가 올바르게
  분리되어 들어가는 것을 redis-cli로 확인.
- 중복 로그인 시나리오(`redis-duplicate-login-check.js`): `socket.io-client`로 같은
  토큰/세션ID를 가진 소켓 2개를 연결한 뒤, `duplicate_login`(즉시)과 `session_ended`(세션
  TTL 만료 시점, 10초 뒤) 이벤트가 정상적으로 도착하는지 확인 — PASS.
  (중간에 `RedisChatDataStore`가 `TypedJsonJacksonCodec` 대신 기본 `JsonJacksonCodec`을 써서
  `InvalidTypeIdException`이 나던 버그가 있었는데, 폴리모픽 타입 힌트 없이 타입을 명시하는
  `TypedJsonJacksonCodec(Class<?>)`로 바꿔 해결했다.)

## 관련 항목

- [1번 항목](01-tomcat-thread-pool.md) — REST 스레드 풀 최적화 이후, 단일 인스턴스 스레드 풀을
  더 올리기보다 인스턴스를 늘리는 쪽이 다음 단계로 더 유효하다고 판단한 근거가 바로 이 항목의
  해결이다(멀티 인스턴스 정합성이 막혀 있었다면 인스턴스를 늘리는 선택지 자체가 없었다).
- [8번 항목](08-redis-unused.md) — 전체 Redis 도입 배경 및 DB index 배정표.
- [10번 항목](10-socketio-config.md) — Socket.IO 설정 전반(이 항목은 그중 `MemoryStoreFactory`
  부분만 다룬다).

## 남은 것

기본값은 여전히 `local`이다 — 실제로 인스턴스를 2대 이상 띄우는 배포에서만
`SOCKETIO_STORE_TYPE=redis`를 켜야 의미가 있다(단일 인스턴스에서는 켜도 손해는 없지만 이득도
없다).
