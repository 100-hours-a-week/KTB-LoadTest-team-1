# 1. Tomcat 스레드 풀이 사실상 없는 수준

> **한줄 요약**: REST API가 동시에 10개 요청만 처리 가능한 설정이었다. 게다가 로컬에서는
> `make dev`가 이 설정 자체를 적용하지 않는 별개의 버그도 있었다. 100/300/1000으로 상향 완료 —
> 2,500명 스파이크 실패율 90.4% → 0%.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 모든 REST API | Critical |

## 문제

`src/main/resources/application.properties:4-7`
```properties
server.tomcat.threads.max=10
server.tomcat.threads.min-spare=1
server.tomcat.accept-count=10
server.tomcat.max-connections=50
```

REST API(로그인/회원가입/방 목록/파일 업로드 등) 전체가 **동시에 최대 10개 요청**만 처리할 수
있었다. `accept-count=10`(대기열 10개) + `max-connections=50`(최대 연결 50개)을 넘어서는 순간부터
연결 자체가 거부된다.

부하테스트에서 동시 사용자를 늘리는 순간 가장 먼저, 가장 확실하게 터지는 지점이다. 다른 최적화를
아무리 해도 이 값이 그대로면 REST 처리량의 상한선이 10 RPS 근처에 묶인다.

## 발견 경위 — 로컬에서는 이 설정 자체가 적용되지 않고 있었다

`make dev`가 원래 쓰던 `spring-boot:test-run`은 클래스패스가
`target/test-classes:target/classes:...` 순서였다. 그 결과 `src/test/resources/application.properties`가
`src/main/resources`의 설정을 완전히 **대체**했다(병합이 아니다 — Spring Boot는
`classpath:/application.properties`를 하나만 읽는다).

이 test 프로퍼티 파일엔 `server.tomcat.threads.*`/`accept-count`/`max-connections`가 아예 없어서,
로컬에서는 Tomcat이 기본값(`maxThreads=200`)으로 돌고 있었다. 스파이크 테스트 중 `jstack`으로
확인한 `http-nio-5001-exec-*` 스레드 수가 정확히 200개였던 게 그 증거다.

`test-run` 자체를 쓸 이유(Testcontainers dev-time 패턴)도 이 레포엔 없었다 — 그 패턴이 요구하는
`TestApplication`류 클래스가 `src/test/java`에 존재하지 않았다.

## 조치 ✅

1. **측정 파이프라인부터 고쳤다.** `Makefile`의 `dev` 타깃을 `spring-boot:test-run` →
   `spring-boot:run`으로 바꿔서 test 클래스패스가 안 걸리게 했다.

   이 과정에서 별개의 버그를 하나 더 발견했다 — `.env`를 자동으로 읽어주는 `spring-dotenv`
   라이브러리가 Spring Boot 4.1에서 작동하지 않았다(`spring.factories` 기반
   `SpringApplicationRunListener` 등록 방식이 이 버전과 안 맞는 것으로 보임). `app.jwt.secret=${JWT_SECRET}`
   같은 필수 플레이스홀더가 해석되지 않아 부팅이 실패했고, `dev` 타깃에 `.env`를 셸에서 직접
   `source`해서 환경변수로 export하는 단계(`set -a && . ./.env && set +a`)를 추가해 우회했다.

   수정 후 40개 동시 요청으로 재검증: `http-nio-5001-exec-*` 스레드가 정확히 **10개**로 캡되는
   것을 확인했다. 이제 `make dev`로 재는 수치가 실제 배포 설정을 정확히 반영한다.

2. **그 다음 실제로 값을 올렸다.** `threads.max=10 → 100`, `threads.min-spare=1 → 5`,
   `accept-count=10 → 300`, `max-connections=50 → 1000`(로컬 14코어 기준, Mongo 드라이버 기본
   커넥션 풀 한도인 100에 맞춰 threads.max를 잡았다).

## 측정 결과

방법론은 [GET_ROOMS_METHODOLOGY.md](GET_ROOMS_METHODOLOGY.md) 참고. 이 항목의 **격리된 효과**는
N+1 제거([03번](03-get-rooms-n-plus-1.md))가 이미 적용된 Baseline #2 위에 스레드 풀만 추가로
올린 Baseline #3과의 차이로 본다.

### 스파이크 테스트 (2,500 VU/10초)

| 지표 | Baseline #2 (N+1만, 스레드 풀 10 그대로) | **Baseline #3 (+ 스레드 풀 100)** |
|---|---|---|
| 완료 | 254건 | **2,540건** |
| **실패(`ERR_SOCKET_TIMEOUT`)** | 2,286건 (90.0%) | **0건 (0%)** |
| p50 | 40.9ms | **15ms** |
| p95 | 3,828.5ms | **30.3ms** |
| p99 | 3,905.8ms | **34.8ms** |
| max | 3,913ms | **48ms** |

### 30 req/s 스무스 램프

| 지표 | Baseline #2 (N+1만) | **Baseline #3 (+ 스레드 풀)** |
|---|---|---|
| **실패(`ERR_SOCKET_TIMEOUT`)** | 621건 (40.5%) | **0건 (0%)** |
| p50 | 854.2ms | **10.9ms** |
| p95 | 963.1ms | **16.9ms** |
| p99 | 1,002.4ms | **21.1ms** |

**해석**

- N+1만 고쳤을 때는 요청이 빨라져도 연결 자체가 거부돼서 생존율이 안 바뀌었다(90.0%).
- 스레드 풀/accept-count/max-connections를 같이 올리니 "연결을 받아줄 슬롯"이 확보되면서
  2,500명 스파이크를 **완전히 버텼다**(실패 0건, p99 34.8ms).
- 순서가 중요했다 — 스레드 풀만 늘리고 N+1을 안 고쳤다면 느린 요청이 스레드 100개를 더 오래
  붙잡아서 이만큼 좋아지지 않았을 것이다(정확히는 별도 검증 필요, 아직 안 함).

**Baseline #1(코드 수정 전 원본) 대비 합산 효과**: 실패율 90.4% → 0%, p99 3,678ms → 34.8ms.
