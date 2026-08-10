# 8. Redis가 인프라엔 있는데 코드에서 전혀 안 쓰인다

> **한줄 요약**: Redis 컨테이너·의존성·환경변수가 다 있는데 실제로 참조하는 코드가 0줄. 세션·rate limit이 전부 MongoDB로 대신 처리되고 있다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | 세션 검증(모든 요청) + rate limit 체크 | Medium |

## 문제

- `docker-compose.yaml`에 Redis 컨테이너가 있다.
- `pom.xml`에 `spring-boot-starter-data-redis`, `redisson`이 의존성으로 들어 있다.
- `.env.template`에 `REDIS_HOST`/`REDIS_PORT`가 필수 환경변수로 요구된다.
- 그런데 실제 Java 코드에서 `RedisTemplate`/`Redisson`을 참조하는 곳이 **한 군데도 없다**
  (`grep -rl "Redis\|redisson\|RedisTemplate" src/main/java` 결과 0건).

세션(`SessionMongoStore`)과 Rate Limit(`RateLimitMongoStore`)은 둘 다 MongoDB를 쓴다 — 백엔드
README에도 "MongoDB TTL 기반 세션·레이트리밋"이라고 명시돼 있다.

세션 검증(모든 REST 요청 + 모든 소켓 이벤트)과 rate limit 체크
([4번 항목](04-chatmessage-duplicate-session.md))가 전부 MongoDB 왕복인데, 이미 띄워져 있는
Redis를 전혀 활용하지 않고 있다.

## 조치 (미착수)

세션/rate limit을 Redis 기반으로 이전하면(특히 rate limit은 Redis `INCR` + `EXPIRE` 한 번으로
원자적 처리 가능) [4번 항목](04-chatmessage-duplicate-session.md)의 병목을 근본적으로 줄일 수 있다.
[9번 항목](09-ratelimit-transactional.md)과 묶어서 처리. 임팩트는 크지만 범위가 넓어 우선순위
로드맵상 후순위.
