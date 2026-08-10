# 성능 측정 기준 (Baseline Protocol)

부하테스트 대회 대비 성능 개선 작업에서, 코드를 고칠 때마다 재현 가능하게 비교하기 위한 측정
방법론. 실제 API별 측정 결과는 `PERFORMANCE_MEASUREMENTS.md`에 이 프로토콜을 그대로 적용해서 기록한다.

측정 스크립트는 `apps/backend/perf-artillery/`에 있다(의도적으로 git에 커밋하지 않음 — `.gitignore` 참고).

## 측정 환경

- 로컬 macOS, 백엔드는 `make dev`(내부적으로 `./mvnw compile spring-boot:run -Dspring-boot.run.profiles=dev`,
  `.env`를 셸에서 직접 export)로 기동 (Mongo/Redis는 `docker-compose.yaml`을
  spring-boot-docker-compose 연동으로 재사용)
- **Tomcat 스레드 풀은 앞으로 이 값으로 고정한다** (`apps/backend/src/main/resources/application.properties`):
  `server.tomcat.threads.max=100`, `threads.min-spare=5`, `accept-count=300`,
  `max-connections=1000`. 아래에서 코드를 고칠 때마다 재는 모든 수치는 이 스레드 풀 설정을 전제로
  한다 — 앞으로의 Baseline 비교(#4, #5, ...)는 이 설정이 "주어진 환경"이고, 그 위에서 다른 코드
  병목(4·5번 항목 등)을 얼마나 줄이는지를 본다.

**과거 경위(참고용)**: 한때는 `make dev`가 `spring-boot:test-run`을 써서 스레드 풀 설정이 로컬에서
전혀 적용되지 않는 버그가 있었고(`PERFORMANCE_BOTTLENECKS.md` 1번 항목), 그걸 고친 뒤 원래 배포
값이던 `threads.max=10`으로 처음 재봤을 때는 스파이크(2,500 VU) 실패율 90.4%였다. N+1 제거만으로는
실패율이 거의 안 줄었고(90.0%, 연결 자체가 거부되는 게 문제라 코드 속도는 무관), 스레드 풀을
10→100으로 올리고 나서야 실패율 0%로 완전히 해소됐다(`PERFORMANCE_MEASUREMENTS.md` Baseline
#1~#3). 그 과정을 거쳐 지금은 위 100 기준값으로 고정한 것이다.

**⚠️ Grafana JVM Threads 패널 관련 참고**: Prometheus가 `/actuator/prometheus`를 5초마다
스크레이핑하면서 Tomcat 워커 스레드를 순환시키기 때문에, 유휴 상태에서도 스레드 수가
`min-spare`(=5)까지 잘 안 줄어들고 어느 정도 값에 머물러 보일 수 있다(버그 아님 — 모니터링 자체의
부작용, `threads.min-spare=1`이던 시절 Prometheus를 잠깐 멈추면 40초 안에 최소치로 줄어드는 것으로
확인함). 배포 환경에서도 동일하게 나타나는 현상이라 오히려 실제에 가까운 그림이다.

## 측정 기준

대회는 스파이크 테스트만 진행하며, 채점은 "서버가 얼마나 오래 살아남는가(생존)"가 관건이다.
공식 수치(순간 동시접속자 수 등)는 아직 공지되지 않아 다음을 직접 고정한다.

- **주 지표(헤드라인) — 스파이크 테스트.** 평상시 → 순간적으로 2,500명이 몰리는 스파이크(10초,
  초당 250명 유입), 2단계(baseline → spike)만 본다. 리커버리(회복 속도)는 보지 않는다 — 채점
  기준이 생존 여부이기 때문. 매 라운드 비교는 이 지표로 한다.
- **보조 지표 — 가벼운 램프(30 req/s).** 세부 수정 효과를 ms 단위로 보는 용도. 스레드 풀 상향
  이후(Baseline #3)로는 이 부하에서 에러 없이 여유 있게 통과한다.

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

다른 엔드포인트를 측정할 때도 이 5단계를 그대로 따르되, 3번의 프로파일 파일만 해당 엔드포인트용으로
새로 만든다(`get-rooms-*.yml`과 같은 패턴).

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
