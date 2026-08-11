# 백엔드 성능 병목 분석 — 인덱스

부하테스트 대회를 앞두고 `apps/backend`(Spring Boot 4.1 / Java 25 / MongoDB / Netty-socketio) 코드를
전수 분석해 병목이 될 만한 지점을 정리했다. 실제로 로컬에서 백엔드를 띄우고 코드 경로를 추적하며
확인한 내용이며, 일부는 실제 API 호출로 재현/검증했다.

**각 항목은 `docs/performance/`에 문제 분석·조치·측정 결과를 모두 담은 개별 문서로 분리되어
있다.** 이 파일은 전체를 훑어보기 위한 요약 표와 링크만 담는다.

`GET /api/rooms` 관련 항목([1](performance/01-tomcat-thread-pool.md)·
[2](performance/02-message-index.md)·[3](performance/03-get-rooms-n-plus-1.md)번)은 측정
방법론을 공유한다 — [performance/GET_ROOMS_METHODOLOGY.md](performance/GET_ROOMS_METHODOLOGY.md) 참고.

---

## 전체 목록

| # | 문제 | 분류 | 상태 |
|---|------|---|---|
| [1](performance/01-tomcat-thread-pool.md) | `server.tomcat.threads.max=10` — REST 동시 처리량이 10개로 고정 | Critical | ✅ 완료 |
| [2](performance/02-message-index.md) | `messages` 컬렉션에 인덱스가 하나도 없음 | Critical | ✅ 완료 |
| [3](performance/03-get-rooms-n-plus-1.md) | `GET /api/rooms` N+1 쿼리 폭발 + 페이지네이션 미적용 | Critical | ⚠️ N+1만 완료 |
| [4](performance/04-chatmessage-duplicate-session.md) | 채팅 메시지 1건당 MongoDB 왕복 9~10회 | Critical | ⚠️ 중복 호출만 제거 |
| [5](performance/05-banned-word-filter.md) | 금칙어 필터가 메시지마다 10,000개 단어 순차 스캔 | Critical | ✅ 완료 |
| [6](performance/06-message-loader-n-plus-1.md) | 히스토리 스크롤이 발신자 정보를 메시지마다 개별 조회 | High | ✅ 완료 |
| [7](performance/07-mark-as-read-bulk-update.md) | 읽음 처리가 메시지 ID 개수만큼 find+save 반복 | High | ✅ 완료 |
| [8](performance/08-redis-unused.md) | Redis가 인프라엔 있는데 코드에서 전혀 안 쓰임 | Medium (인프라) | 미착수 |
| [9](performance/09-ratelimit-transactional.md) | rate limit `@Transactional`이 사실상 무의미 | Medium (인프라) | 미착수 |
| [10](performance/10-socketio-config.md) | Socket.IO 서버 설정(acceptBackLog/tcpNoDelay/단일노드) | Medium (인프라) | 미착수 |
| [11](performance/11-jvm-heap.md) | JVM 힙 기본값이 작다(`-Xmx1024m`) | Medium (인프라) | 미착수 |
| [12](performance/12-file-upload-io.md) | 파일 업로드가 동기 디스크 I/O | Medium | 미착수 |
| [13](performance/13-reconnect-leave.md) | 소켓 재접속이 "방 나가기"로 처리됨 | 기능 버그 + 성능 | ⏸️ 프론트 수정 필요 |
| [14](performance/14-participant-list-n-plus-1.md) | 방 입장·퇴장마다 참가자 목록을 개별 조회 | Critical | ✅ 완료 |

## 실측 요약

| 항목 | 지표 | 결과 |
|---|---|---|
| [1·2·3](performance/GET_ROOMS_METHODOLOGY.md) | `GET /api/rooms` 스파이크(2,500 VU/10초) | 실패율 90.4% → **0%**, p99 3,678ms → **34.8ms** |
| [4](performance/04-chatmessage-duplicate-session.md) | 메시지 1,000건 동시 전송, 서버 처리 시간 | 평균 14.74ms → **10.52ms (-28.6%)** |
| [5](performance/05-banned-word-filter.md) | `containsBannedWord` 메서드 단위 벤치마크(20만 회) | 216us → **0.17us/call (약 1,300배)** |
| [6](performance/06-message-loader-n-plus-1.md) | 발신자 30명(전부 다름)짜리 히스토리 조회 | p50 10ms → **6ms (-40%)** |
| [7](performance/07-mark-as-read-bulk-update.md) | 유저 30명, 메시지 200건 동시 읽음 처리 | p50 240ms → **43ms (-82%)**, N에 비례 → 상수 시간 |
| [13](performance/13-reconnect-leave.md) | 유저 20명×방 5개 동시 재접속 | 퇴장 스팸 메시지 100건 → **0건** (현재 롤백 상태) |
| [14](performance/14-participant-list-n-plus-1.md) | 참가자 30명 방 입장/퇴장(순차) | joinRoom p50 21ms → **14ms**, leaveRoom p50 22ms → **11ms** |
| [14](performance/14-participant-list-n-plus-1.md) | 동시 30명 입장/퇴장(배경 참가자 50명, p95/p99) | join p50 -37%/p95 -25%, leave p50 -20%/p95 거의 동일 |

이 항목들만 고쳐도 처리량이 수십 배 단위로 개선될 가능성이 크다.

---

## 우선순위 로드맵

**원칙**: 비즈니스 로직 레벨(코드 안에서 바로 고칠 수 있는 것)을 먼저 처리하고, Redis 이전 같은
인프라 레벨 개선은 뒤로 미룬다.

### 1단계 — 이미 처리한 것

1. [1번] Tomcat 스레드 풀 상향 — **✅ 완료.**
2. [4번] `ChatMessageHandler`의 중복 `updateLastActivity` 제거 — **✅ 완료.**
3. [2번] `Message`에 `{room:1, timestamp:-1}` 복합 인덱스 추가 — **✅ 완료.**
4. [3번] `RoomService.getAllRooms`/`mapToRoomResponse` N+1 제거 — **✅ N+1 완료** (페이지네이션은 보류).
5. [7번] `MessageReadStatusService.updateReadStatus`를 벌크 업데이트로 교체 — **✅ 완료.**
6. [6번] 히스토리 스크롤 발신자 조회 N+1 제거 — **✅ 완료.**
7. [5번] 금칙어 필터를 Aho-Corasick으로 교체 — **✅ 완료.**
8. [14번] 방 입장·퇴장 시 참가자 목록 개별 조회 N+1 제거 — **✅ 완료.** (감사 중 신규 발견)

### 2단계 — 다음으로 볼 것 (비즈니스 로직 레벨)

9. [4번 남은 부분] `notifyMessageStored`의 동기 COUNT 쿼리를 인메모리 캐시로 교체. **멀티인스턴스
   배포가 계획돼 있어 JVM 로컬 캐시는 부적합** — 지금 방식(MongoDB 쿼리, 인스턴스 무관하게 항상
   정확) 유지가 안전하다. Redis로 옮길 만큼 우선순위가 높지는 않다고 판단해 보류.
10. [13번] 소켓 재접속 — 백엔드 수정은 준비됐지만 프론트(`beforeunload`) 수정과 함께 진행해야 완전히 해소.
11. [12번] 파일 업로드 동기 디스크 I/O — 부하테스트에 파일 업로드가 포함될 경우. **방향은 client→S3
    presigned URL로 결정**(프론트 예외 필요), 구현은 보류 중.

> **⚠️ 별도로 발견한 멀티인스턴스 이슈**: 백엔드가 멀티인스턴스로 배포될 예정인데,
> `SocketIOConfig`의 `MemoryStoreFactory()`([10번](performance/10-socketio-config.md))와
> `LocalChatDataStore`(`ConnectedUsers`/`UserRooms`를 백업, 현재 문서화 안 됨)가 둘 다 JVM
> 로컬 상태라서 인스턴스가 2개 이상이면 브로드캐스트 누락·중복 로그인 오탐지·재접속 처리 오류가
> 조용히 발생할 수 있다. 성능이 아니라 **정합성 문제**라 원래 로드맵보다 우선순위를 올려야 할
> 수 있다 — 아직 항목 문서화도, 우선순위 배치도 안 된 상태. 다음에 다룰 때 반영 필요.

### 3단계 — 인프라 레벨 (후순위)

12. [8][9번] 세션/rate limit을 Redis로 이전 — 이미 떠 있는 인프라를 활용하는 구조적 개선. 임팩트는 크지만 범위가 넓다.
13. [10번] Socket.IO `acceptBackLog`/`tcpNoDelay` 튜닝 — 연결 폭주 시나리오(`ramp-up-test.js`) 대비.
14. [11번] JVM 힙 크기 조정 — `apps/backend` 안에는 없음(`app-control.sh`가 systemd 서비스로
    기동, 실제 힙 값은 레포 밖 systemd 유닛 파일에 있음). 로컬 `make dev`용 `Makefile`의
    `JVM_OPTS`만 이 레포 스코프.

각 항목을 고친 뒤에는 같은 시나리오로 baseline 대비 처리량/지연시간을 비교하고,
`apps/backend/monitoring/grafana/provisioning/dashboards/spring-boot-app-dashboard.json`
대시보드(HTTP Request Rate/Latency, JVM Heap, GC Pause Time)로 실제 개선 여부를 확인할 것을 권장한다.
