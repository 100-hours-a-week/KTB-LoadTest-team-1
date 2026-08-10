# 9. `RateLimitService.checkRateLimit`의 `@Transactional`은 사실상 무의미하고, 원자성도 없다

> **한줄 요약**: `@Transactional`이 붙어있지만 트랜잭션 매니저 자체가 없어서 조용히 no-op 처리된다. rate limit이 동시 요청에서 과소 집계될 수 있다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | rate limit 정확성 (처리량보다는 정확성 이슈) | Medium |

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

즉 `find → count 비교 → save`로 이어지는 read-then-write 로직에 실질적인 원자성 보장이 없다.
같은 클라이언트가 여러 요청을 동시에 보내면(동일 유저가 여러 탭/여러 소켓 이벤트를 거의 동시에
쏘는 상황) 두 요청이 같은 카운트를 읽고 각자 +1 해서 저장하는 lost-update가 가능해 rate limit이
과소 집계될 수 있다.

처리량 자체보다는 **정확성 문제**지만, "제한이 걸려야 하는데 안 걸림"으로 부하테스트 결과 해석에
혼선을 줄 수 있어 기록해둔다.

## 조치 (미착수)

[8번 항목](08-redis-unused.md)과 묶어서 Redis `INCR`/Lua 스크립트 기반의 원자적 rate limiter로
교체.
