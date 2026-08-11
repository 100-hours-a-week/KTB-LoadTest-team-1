# 8. Redis가 인프라엔 있는데 코드에서 전혀 안 쓰인다

> **한줄 요약**: Redis 컨테이너·의존성·환경변수가 다 있는데 실제로 참조하는 코드가 0줄이었다.
> 세션·rate limit·Socket.IO 크로스 인스턴스 스토어를 전부 Redis로 옮길 수 있는 옵션을 추가했다
> (기본값은 그대로 mongo/local이라 아무것도 안 바꾸면 기존과 동일하게 동작).

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 완료(옵트인) | 세션 검증(모든 요청) + rate limit 체크 + Socket.IO 크로스 인스턴스 스토어 | Medium |

## 문제 (원래 상태)

- `docker-compose.yaml`에 Redis 컨테이너가 있다.
- `pom.xml`에 `spring-boot-starter-data-redis`, `redisson`이 의존성으로 들어 있다.
- `.env.template`에 `REDIS_HOST`/`REDIS_PORT`가 필수 환경변수로 요구된다.
- 그런데 실제 Java 코드에서 `RedisTemplate`/`Redisson`을 참조하는 곳이 **한 군데도 없었다**.

세션(`SessionMongoStore`)과 Rate Limit(`RateLimitMongoStore`)은 둘 다 MongoDB를 썼고, Socket.IO
크로스 인스턴스 스토어([16번 항목](16-multi-instance-socketio.md))는 JVM-local
`MemoryStoreFactory`/`LocalChatDataStore`였다.

## 조치 ✅

각 기능을 독립적인 프로퍼티로 mongo/local(기본) ↔ redis 전환 가능하게 만들었다. 기존
`spring.data.redis.*`(아무 코드도 안 쓰던 죽은 설정, `REDIS_HOST`/`PORT`/`PASSWORD`)는 정리했다.

| 기능 | 프로퍼티 | 기본값 | Redis 켰을 때 구현체 |
|---|---|---|---|
| 세션 | `session.store.type` | `mongo` | `SessionRedisStore` |
| rate limit | `ratelimit.store.type` | `mongo` | `RateLimitRedisStore`(원자적 INCR, [9번 항목](09-ratelimit-transactional.md)) |
| Socket.IO 스토어 | `socketio.store.type` | `local` | `RedissonStoreFactory` + `RedisChatDataStore`([16번 항목](16-multi-instance-socketio.md)) |

**Redis 인스턴스 구성**: 세션/rate-limit은 같은 물리 Redis 인스턴스를 공유하되(`REDIS_SESSION_HOST/PORT/PASSWORD`)
DB index로 분리한다. Socket.IO도 별도 인스턴스(`REDIS_SOCKETIO_HOST/PORT/PASSWORD`)를 쓰거나, 운영
편의상 같은 물리 인스턴스로 합쳐도 안전하도록 전역적으로 DB index를 조율해뒀다:

| DB index | 용도 |
|---|---|
| 0 | Socket.IO 자체 스토어(room/브로드캐스트) |
| 1 | ChatDataStore(`ConnectedUsers`/`UserRooms`) |
| 2 | 세션 |
| 3 | rate limit |

세션/rate-limit을 물리적으로 분리한 이유: 둘 다 요청 경로에서 매번 치는 단순 KV/TTL 패턴이라
성격이 비슷한 반면, Socket.IO는 pub/sub 트래픽(부하테스트로 크게 몰릴 수 있음)이라 서로 영향을
안 주게 격리하는 게 이상적이다(운영 부담 때문에 합쳐 써도 DB index 덕분에 데이터는 안 섞인다).

## 측정/검증

- **세션**: 실제 Redis로 회원가입→로그인→인증된 요청→재로그인(단일 세션 정책으로 기존 세션
  무효화, 예전 토큰 401/새 토큰 200)→로그아웃(키 삭제)까지 전체 라이프사이클 확인. TTL도
  `Session.SESSION_TTL`(30분)과 정확히 일치.
- **rate limit 저장소 자체의 지연시간**(mongo vs redis, 순차 150회 요청): 로컬 환경 기준
  유의미한 차이 없음(오히려 Redis가 JSON 직렬화 오버헤드로 p50 18ms→20ms 소폭 느림) — 이
  마이그레이션의 핵심 가치는 속도가 아니라 [9번 항목](09-ratelimit-transactional.md)의 원자성
  문제 해결이었다.
- **DB index 분리**: 세션+rate-limit을 동시에 Redis 모드로 켜고 redis-cli로 DB 2/DB 3에 각자
  키가 올바르게 들어가는 것을 직접 확인.

## 남은 것

세션/rate-limit Redis 코드는 다 있지만 **기본값은 여전히 mongo**다 — 배포 환경에서
`SESSION_STORE_TYPE=redis`/`RATELIMIT_STORE_TYPE=redis`를 실제로 켜야 효과가 난다(Mongo도 이미
공유 스토어라 정합성 문제는 없어서, 이건 순수 성능 옵션이지 필수 수정은 아니다).
