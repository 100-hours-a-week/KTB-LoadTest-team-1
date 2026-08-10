# 4. 채팅 메시지 1건당 MongoDB 왕복 9~10회 (전부 동기 블로킹)

> **한줄 요약**: 메시지 1건 처리에 순차 DB 왕복이 9~10회 붙는다. 그중 세션 갱신 2회는 완전한
> 중복이었다 — 제거해서 서버 처리 시간 -28.6%. 나머지 7회는 아직 남아있다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ⚠️ 중복 호출 제거만 완료, 나머지 왕복은 미착수 | 채팅 메시지 처리량(Socket.IO) | Critical |

## 문제

`src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`

`handleChatMessage` 한 번 호출(`chatMessage` 이벤트 하나) 안에서 발생하는 순차 DB 호출:

| 줄 | 호출 | Mongo 왕복 |
|---|------|-----------|
| 81 | `sessionService.validateSession(...)` | find + save (lastActivity 갱신) = 2회 |
| 94 | `rateLimitService.checkRateLimit(...)` | find + save = 2회 |
| 113 | `userRepository.findById(socketUser.id())` | 1회 |
| 125 | `roomRepository.findById(roomId)` | 1회 |
| 164 | `messageRepository.save(message)` | 1회 |
| 171 | `roomActivityNotifier.notifyMessageStored(roomId)` → `countRecentMessagesByRoomId` | 1회(2번 항목 인덱스로 해소) |
| 176 | `sessionService.updateLastActivity(socketUser.id())` | find + save = 2회 |

**176번 줄은 81번 줄과 완전히 중복이다.** `validateSession` 내부에서 이미 `lastActivity`를 갱신하고
저장하는데(`SessionService.java:70-108`), 메시지 처리 끝에서 `updateLastActivity`를 한 번 더
호출해 같은 세션 도큐먼트를 또 find+save 한다 — 메시지 1건당 세션 관련 Mongo 왕복만 4회.

합치면 텍스트 메시지 1건에 **최소 9회**(파일 메시지는 `fileRepository.findById` 추가로 10~11회)의
순차 blocking MongoDB 호출이 발생한다. 이게 전부 Netty-socketio 워커 스레드 위에서 동기적으로
실행된다(`@Async`/전용 executor 전무 — 코드베이스 전체에 `@EnableAsync` 없음). 동시 메시지
처리량은 사실상 "워커 스레드 수 ÷ 메시지당 총 DB 지연시간"으로 수렴한다.

## 조치

- 176번 줄의 중복 `updateLastActivity` 호출 제거 (즉시 적용 가능, 리스크 없음). **✅ 완료.**
- rate limit/세션 검증을 Redis 기반으로 교체([8번 항목](08-redis-unused.md) 참고) 해 라운드트립
  자체를 없애기. 미착수 — **인프라 레벨 개선이라 후순위.**
- `roomActivityNotifier.notifyMessageStored`를 동기 카운트 쿼리 대신 인메모리 카운터/캐시로
  교체. 미착수.

### ✅ 중복 `updateLastActivity` 호출 제거 완료

`ChatMessageHandler.java`에서 176번 줄을 삭제했다. `validateSession`이 이미 같은 세션 도큐먼트의
`lastActivity`/`expiresAt`을 갱신·저장하므로, 뒤에서 다시 find+save하는 건 순수한 중복이었다.

```java
// AI 멘션 처리
aiService.handleAIMentions(roomId, socketUser.id(), messageContent);

// sessionService.validateSession(...)이 이미 lastActivity/expiresAt을 갱신·저장하므로
// 여기서 다시 updateLastActivity를 호출하면 같은 세션 도큐먼트를 find+save로 한 번 더
// 왕복하는 완전한 중복 작업이다.
```

메시지 1건당 세션 관련 Mongo 왕복이 4회 → 2회로 줄어 텍스트 메시지 기준 총 왕복이 9회 → 7회가
됐다.

## 측정 방법

스크립트: `apps/backend/perf-artillery/chat-message-load.js` (git 미추적).

**시나리오**: 유저 50명을 방 1개에 소켓으로 동시 조인시킨 뒤, 대기 없이 각자 메시지 20건씩(총
1,000건)을 최대한 빠르게 동시 전송한다(ack 대기 없음). REST API로 유저 생성/로그인 + 방 생성까지
스크립트가 자동 처리한다.

**지표 두 가지를 함께 본다**:

- **주 지표 — 서버 처리 시간**: Prometheus `/actuator/prometheus`의
  `socketio_messages_processing_time_seconds{status="success",message_type="text"}`
  (`handleChatMessage` 진입부터 성공 응답 직전까지를 순수하게 잰 값). `_count`/`_sum`으로 평균
  (`sum/count`)을 구한다. DB 왕복 수 변화가 가장 직접적으로 드러나는 지표라 이걸 헤드라인으로
  삼았다.
- **보조 지표 — 소켓 RTT**: emit부터 방 브로드캐스트로 echo를 받기까지의 왕복 시간. 다만 이
  값에는 메시지 1건당 51회(방 전체 브로드캐스트 50 + 발신자 본인 echo 1)의 소켓 전송 비용과
  클라이언트 쪽 이벤트 처리 비용까지 섞여 있어서, 이번에 줄인 DB 왕복 2회(로컬 Mongo 기준 대략
  수 ms)가 그 안에 묻힐 수 있다.

### 재현 명령

```bash
# 1. 코드를 원하는 상태(수정 전/후)로 맞추고 백엔드 재기동
cd apps/backend && make dev

# 2. 부하 스크립트 실행 (별도 터미널)
cd apps/backend/perf-artillery
NODE_PATH=<repo-root>/loadtest/node_modules node chat-message-load.js \
  --users=50 --messages-per-user=20

# 3. 서버 처리 시간(주 지표) 확인 — 부하 스크립트 실행 직후
curl -s http://localhost:5001/actuator/prometheus | grep socketio_messages_processing_time_seconds
```

Prometheus 카운터는 재기동 시 초기화된다 — "수정 전 재기동 → 측정 → 수정 적용 → 재기동 → 측정"
순으로 **매 측정마다 깨끗한 카운터에서 시작**해야 한다.

## 측정 결과

### 주 지표 — 서버 처리 시간

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| 메시지 수 | 1,000건 | 1,000건 |
| 평균 처리 시간(sum/count) | 14.74ms | **10.52ms (-28.6%)** |
| max | 41.96ms | **28.12ms (-33.0%)** |

재현성 확인차 수정 후 상태로 한 번 더 측정: 평균 10.77ms, max 32.43ms — ±3% 이내로 안정적이었다.

**해석**: 메시지당 순수 서버 처리 시간이 약 4.2ms(28.6%) 줄었다. "Mongo 왕복 2회 제거"만큼 정확히
맞아떨어지는 개선이다 — 로컬 Docker Mongo 기준 왕복 1회가 대략 2ms 안팎이라는 뜻이고, 배포
환경에서 DB와의 네트워크 거리가 더 멀면 절대적인 절감폭은 더 커질 가능성이 크다.

### 보조 지표 — 소켓 RTT(클라이언트 관측)

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| 전체 처리 시간(첫 emit ~ 마지막 echo) | 1,506ms | **1,307ms** |
| 처리량 | 664.0 msg/s | **765.1 msg/s** |
| p50 | 887ms | **770ms** |
| p95 | 1,401ms | **1,235ms** |
| p99 | 1,440ms | **1,276ms** |
| max | 1,456ms | **1,289ms** |

**해석**: 방향은 일관되게 개선(처리량 +15%, p50/p95/p99 전부 단축)되지만, 수정 전 상태를 반복
측정했을 때도 664~763 msg/s 사이로 편차가 있었던 걸 감안하면 이 지표 하나만으로 "28.6% 개선"을
주장하긴 어렵다. 50명에게 매 메시지를 브로드캐스트하는 팬아웃 비용이 왕복 시간 대부분을 차지하고,
이번에 제거한 Mongo 왕복 2회는 그 안에서 상대적으로 작은 비중이기 때문이다. **주 지표(서버 처리
시간)가 이 수정의 효과를 훨씬 깨끗하게 보여준다.**

## 남은 일

텍스트 메시지 기준 순차 Mongo 왕복이 9회 → 7회로 줄었을 뿐, 나머지는 그대로 남아있다:

- 유저/방 조회(`userRepository.findById`, `roomRepository.findById`) — 캐시 없이 매번 조회.
  **비즈니스 로직 레벨 개선이라 이쪽을 먼저 볼 가치가 있다.**
- `roomActivityNotifier.notifyMessageStored`의 동기 COUNT 쿼리 — 인메모리 카운터/캐시로 대체 가능.
- rate limit/세션의 Redis 이전 ([8](08-redis-unused.md)·[9번 항목](09-ratelimit-transactional.md)) —
  인프라 레벨 개선, 후순위.

이 테스트는 로컬 단일 Mongo 컨테이너 기준이라 왕복 1회당 비용이 매우 작다(대략 2ms). 실제 배포
환경(DB가 별도 네트워크 홉을 거치는 경우)에서는 왕복 횟수 감소의 절대적 효과가 이보다 클 가능성이
높다 — 배포 환경에서 같은 스크립트로 재측정해볼 가치가 있다.
