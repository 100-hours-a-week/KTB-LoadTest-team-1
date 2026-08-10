# 측정 방법론: `GET /api/rooms` (Baseline Protocol)

> [01](01-tomcat-thread-pool.md)·[02](02-message-index.md)·[03](03-get-rooms-n-plus-1.md)번
> 항목은 전부 `GET /api/rooms` 스파이크 테스트라는 같은 측정 기준을 공유한다. 방법론은 여기
> 한 곳에 모아두고, 각 항목 문서에서는 결과표만 싣는다.

측정 스크립트는 `apps/backend/perf-artillery/`에 있다(의도적으로 git에 커밋하지 않음 —
`.gitignore` 참고).

## 측정 환경

- 로컬 macOS, 백엔드는 `make dev`로 기동한다. 내부적으로
  `./mvnw compile spring-boot:run -Dspring-boot.run.profiles=dev`를 실행하고, `.env`를 셸에서
  직접 export한다. Mongo/Redis는 `docker-compose.yaml`을 spring-boot-docker-compose 연동으로
  재사용한다.
- **Tomcat 스레드 풀은 앞으로 이 값으로 고정한다** (`application.properties`):
  `threads.max=100`, `threads.min-spare=5`, `accept-count=300`, `max-connections=1000`.
  [01번 항목](01-tomcat-thread-pool.md) 수정 이후로 재는 모든 수치는 이 설정을 전제로 한다.

**과거 경위(참고용)**: 한때는 `make dev`가 `spring-boot:test-run`을 써서 스레드 풀 설정이 로컬에서
전혀 적용되지 않는 버그가 있었다([01번 항목](01-tomcat-thread-pool.md)). 그걸 고친 뒤 원래 배포
값이던 `threads.max=10`으로 처음 재봤을 때는 스파이크(2,500 VU) 실패율이 90.4%였다. N+1 제거만
으로는 실패율이 거의 안 줄었다(90.0%, 연결 자체가 거부되는 게 문제라 코드 속도는 무관). 스레드
풀을 10→100으로 올리고 나서야 실패율 0%로 완전히 해소됐다. 그 과정을 거쳐 지금은 위 100
기준값으로 고정한 것이다.

**⚠️ Grafana JVM Threads 패널 관련 참고**: Prometheus가 `/actuator/prometheus`를 5초마다
스크레이핑하면서 Tomcat 워커 스레드를 순환시킨다. 그래서 유휴 상태에서도 스레드 수가
`min-spare`(=5)까지 잘 안 줄어들고 어느 정도 값에 머물러 보일 수 있다 — 버그가 아니라 모니터링
자체의 부작용이다(`threads.min-spare=1`이던 시절 Prometheus를 잠깐 멈추면 40초 안에 최소치로
줄어드는 것으로 확인함). 배포 환경에서도 동일하게 나타나는 현상이라 오히려 실제에 가까운
그림이다.

## 측정 기준

대회는 스파이크 테스트만 진행하며, 채점은 "서버가 얼마나 오래 살아남는가(생존)"가 관건이다.
공식 수치(순간 동시접속자 수 등)는 아직 공지되지 않아 다음을 직접 고정한다.

- **주 지표(헤드라인) — 스파이크 테스트.** 평상시 → 순간적으로 2,500명이 몰리는 스파이크(10초,
  초당 250명 유입), 2단계(baseline → spike)만 본다. 리커버리(회복 속도)는 보지 않는다 — 채점
  기준이 생존 여부이기 때문. 매 라운드 비교는 이 지표로 한다.
- **보조 지표 — 가벼운 램프(30 req/s).** 세부 수정 효과를 ms 단위로 보는 용도. 스레드 풀 상향
  이후([01번 항목](01-tomcat-thread-pool.md) 완료)로는 이 부하에서 에러 없이 여유 있게 통과한다.

## 절차

1. **DB 상태 고정** — 측정 전 `reset-db.sh`로 `rooms`/`messages`/`users`/`sessions`/`rate_limits`
   컬렉션을 전부 비운 뒤 `seed-rooms.js`로 정해진 규모만 시딩한다. 매번 빈 DB에서 시작.
2. **시드 규모 고정** — 방 200개 × 참가자 5명/방 (`loadtest/ramp-up-test.js` 기본값 "최대 500명,
   ~100-250개 방"의 중간값).
3. **부하 프로파일**
   - 주 지표: `get-rooms-spike.yml` — baseline 20초(2 req/s) → spike 10초 동안 2,500 VU 유입
     (`arrivalCount: 2500`, 초당 250명).
   - 보조 지표: `get-rooms-baseline.yml` — 웜업 5초(1 req/s) → 60초 동안 1→30 req/s 램프 →
     30 req/s로 20초 유지.
4. **비교 지표** — `http.response_time`의 p50/p95/p99, `vusers.failed`(실패율 + 에러 종류),
   실제 처리된 `http.request_rate`. 필요하면 측정 구간의 Grafana `spring-boot-app-dashboard`
   (CPU/힙/GC)도 같이 남긴다.
5. **코드 상태 명시** — 각 결과 섹션에 "코드 수정 전/후" 또는 관련 git 상태를 명시한다.

> **중요 — 01/02/03번은 실제로는 두 단계로 묶어서 고쳤다.** N+1 제거([03](03-get-rooms-n-plus-1.md))와
> `{room,timestamp}` 인덱스 추가([02](02-message-index.md))를 같은 코드 변경에 함께 적용했고,
> 스레드 풀 확장([01](01-tomcat-thread-pool.md))은 그 위에 별도로 얹었다. 그래서 Baseline #1→#2
> 구간은 02+03의 **합산 효과**이고, Baseline #2→#3 구간이 01의 **격리된 효과**다 — 02와 03을
> 서로 분리해서 측정하지는 않았다.

## 재현 명령

```bash
cd apps/backend/perf-artillery
./reset-db.sh
node seed-rooms.js --rooms=200 --participants=5 --api-url=http://localhost:5001 > .token

# 주 지표 (스파이크 테스트)
API_TOKEN="$(cat .token)" ../../../e2e/node_modules/.bin/artillery run get-rooms-spike.yml --target http://localhost:5001

# 보조 지표
API_TOKEN="$(cat .token)" ../../../e2e/node_modules/.bin/artillery run get-rooms-baseline.yml --target http://localhost:5001
```
