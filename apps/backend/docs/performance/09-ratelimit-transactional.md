# 9. `RateLimitService.checkRateLimit`의 `@Transactional`은 사실상 무의미하고, 원자성도 없다

> **한줄 요약**: `@Transactional`이 붙어있지만 트랜잭션 매니저 자체가 없어서 조용히 no-op 처리된다.
> 실측해보니 "약간 과소 집계"가 아니라 **동시 요청의 92%가 아예 카운트되지 않는** 수준이었다.
> Redis `INCR`+Lua 스크립트 기반 원자적 rate limiter를 옵션으로 추가해 해결.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 완료(옵트인, `ratelimit.store.type=redis`) | rate limit 정확성 | Medium |

## 문제

`src/main/java/com/ktb/chatapp/service/RateLimitService.java:39`

```java
@Transactional
public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
```

- 코드베이스 전체에 `MongoTransactionManager` 빈 정의가 없다(`grep` 결과 0건).
- `docker-compose.yaml`의 Mongo도 `--replSet` 없는 standalone이라 애초에 MongoDB 트랜잭션을 못 쓴다.
- 실제로 로그인 후 `GET /api/rooms`(rate limit 적용 엔드포인트)를 호출해보면 정상적으로 200을
  반환한다 — 트랜잭션 매니저 빈이 없으면 Spring이 `@Transactional`을 예외 없이 조용히 no-op으로
  넘긴다는 뜻이다.

`find → count 비교 → save`로 이어지는 read-then-write 로직에 실질적인 원자성 보장이 없다는 것
자체는 원래 파악하고 있었지만, 처음엔 "동시 요청 시 약간의 lost-update가 날 수 있는 정도"로
가볍게 봤다.

## 실측 — 예상보다 훨씬 심각했다

`ratelimit-concurrency-check.js`로 동일 클라이언트가 `GET /api/rooms`에 진짜 동시 요청 N개를
쏘고, 서버가 최종 집계한 카운트(`X-RateLimit-Limit − X-RateLimit-Remaining`)를 확인:

| 저장소 | 보낸 요청 | 서버가 집계한 카운트 | 유실 |
|---|---|---|---|
| Mongo(기존) | 51 | **4** | **47건(92%)** |
| Redis(원자적 INCR) | 51 | **51** | 0건 |
| Redis(원자적 INCR) | 100 | **101**(확인용 1회 포함) | 0건 |

원인은 lost-update가 아니라 더 근본적이었다: `clientId`에 유니크 인덱스가 걸려 있는 상태에서
동시에 첫 요청들이 몰리면 대부분이 insert 경쟁에서 `DuplicateKeyException`을 던지는데, 이걸
`RateLimitService`의 범용 `catch (Exception e)` fail-open 핸들러가 조용히 삼켜서 "카운트하지
않고 그냥 통과"시키고 있었다. 즉 **동시 부하 상황에서는 rate limit이 사실상 거의 작동하지
않는 것**과 같았다 — 부하테스트로 동시성이 커질수록 정확히 더 안 걸리는, 최악의 방향으로
악화되는 버그였다.

## 조치 ✅

[8번 항목](08-redis-unused.md)과 묶어서 Redis 원자적 카운터로 교체했다. `RateLimitStore`에
`tryAtomicCheckAndIncrement`를 추가하고, `RateLimitRedisStore`가 Lua 스크립트로 구현:

```java
private static final String CHECK_AND_INCREMENT_SCRIPT =
        "local current = redis.call('INCR', KEYS[1]) " +
        "if current == 1 then " +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
        "end " +
        "local ttl = redis.call('TTL', KEYS[1]) " +
        "return {current, ttl}";
```

`INCR`+최초 1회만 `EXPIRE`를 한 Lua 스크립트로 묶어서 원자적으로 실행(`RScript.Mode.READ_WRITE`)하기
때문에, 동시 요청이 몰려도 "읽고 비교하고 쓰는" 사이 경쟁이 생길 여지 자체가 없다. `RateLimitService`는
이 원자적 경로를 우선 시도하고, 없으면(=Mongo 모드) 기존 find-check-save 로직으로 폴백한다(Mongo
경로 자체는 변경 없음 — 기존 `RateLimitServiceUnitTest` 8개 모두 무수정으로 통과).

Mongo 모드로 남겨두는 것 자체는 손해가 아니다 — 다만 위 실측처럼 동시 부하가 커지는 조건에서는
`ratelimit.store.type=redis`를 켜는 쪽이 훨씬 안전하다.

## 참고

- 저장소 자체의 지연시간(mongo vs redis) 비교는 [8번 항목](08-redis-unused.md) 참고 — 이 조치의
  목적은 속도가 아니라 정확성이었다.
