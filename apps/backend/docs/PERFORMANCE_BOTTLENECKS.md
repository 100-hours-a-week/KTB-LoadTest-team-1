# 백엔드 성능 병목 분석

부하테스트 대회를 앞두고 `apps/backend`(Spring Boot 4.1 / Java 25 / MongoDB / Netty-socketio) 코드를
전수 분석해 병목이 될 만한 지점을 정리했다. 실제로 로컬에서 백엔드를 띄우고 코드 경로를 추적하며
확인한 내용이며, 일부는 실제 API 호출로 재현/검증했다.

각 항목에 파일 경로와 줄 번호를 남겼으니 바로 찾아가서 고칠 수 있다.

---

## 요약: 임팩트가 가장 큰 5가지

| # | 문제 | 영향 범위 | 상태 |
|---|------|-----------|---|
| 1 | `server.tomcat.threads.max=10` — REST 동시 처리량이 10개로 고정 | **모든 REST API** | ✅ 수정 완료 (100/300/1000으로 상향) |
| 2 | `messages` 컬렉션에 인덱스가 하나도 없음 | 메시지 전송, 방 목록, 히스토리 스크롤, 읽음 처리 전부 | ✅ 수정 완료 (`{room,timestamp}` 인덱스 추가) |
| 3 | `GET /api/rooms`가 방×참가자 수만큼 N+1 쿼리 폭발 + 페이지네이션 미적용 | 방 목록 조회(가장 빈번한 엔드포인트) | ⚠️ N+1은 수정 완료, 페이지네이션은 미착수 |
| 4 | 채팅 메시지 1건당 MongoDB 왕복 최소 9~10회, 전부 동기 블로킹 | 채팅 메시지 처리량(Socket.IO) | 미착수 |
| 5 | 금칙어 필터가 메시지 1건마다 10,000개 단어를 전부 `contains()`로 스캔 | 채팅 메시지 처리량(CPU) | 미착수 |

`GET /api/rooms` 스파이크 테스트 기준(2,500 VU/10초) 1·2·3(N+1) 항목을 고친 결과: **실패율
90.4% → 0%**, p99 3,678ms → 34.8ms. 실측 근거는 `PERFORMANCE_MEASUREMENTS.md` 참고.

이 5가지만 고쳐도 처리량이 수십 배 단위로 개선될 가능성이 크다. 아래에 근거와 함께 전체 목록을 정리한다.

---

## Critical

### 1. Tomcat 스레드 풀이 사실상 없는 수준 (✅ 수정 완료)

`src/main/resources/application.properties:4-7`
```properties
server.tomcat.threads.max=10
server.tomcat.threads.min-spare=1
server.tomcat.accept-count=10
server.tomcat.max-connections=50
```

REST API(로그인/회원가입/방 목록/파일 업로드 등) 전체가 **동시에 최대 10개 요청**만 처리할 수 있다.
`accept-count=10`(대기열 10개) + `max-connections=50`(최대 연결 50개)을 넘어서는 순간부터 연결 자체가
거부된다. 부하테스트에서 동시 사용자를 늘리는 순간 가장 먼저, 가장 확실하게 터지는 지점이다.
다른 최적화를 아무리 해도 이 값이 그대로면 REST 처리량의 상한선이 10 RPS 근처에 묶인다.

**✅ 수정됨 — 한때는 `make dev`로 이 설정이 전혀 적용되지 않았다.** `make dev`가 원래 쓰던
`spring-boot:test-run`은 클래스패스가 `target/test-classes:target/classes:...` 순서라
`src/test/resources/application.properties`가 `src/main/resources`의 설정을 완전히 대체했는데
(병합 아님 — Spring Boot는 `classpath:/application.properties`를 하나만 읽는다), 이 test
프로퍼티 파일엔 `server.tomcat.threads.*`/`accept-count`/`max-connections`가 빠져 있어서
Tomcat이 기본값(`maxThreads=200`)으로 돌고 있었다. 스파이크 테스트 중 `jstack`으로 확인한
`http-nio-5001-exec-*` 스레드 수가 정확히 200개였던 게 그 증거다. 게다가 이 test-run 자체를
쓸 이유(Testcontainers dev-time 패턴)도 이 레포엔 없었다 — `src/test/java`에 그 패턴이 요구하는
`TestApplication`류 클래스가 존재하지 않았다.

**조치 내용**: `Makefile`의 `dev` 타깃을 `spring-boot:test-run` → `spring-boot:run`으로 바꿔서
test 클래스패스가 안 걸리게 했다. 이 과정에서 별개의 버그를 하나 더 발견했다 — `.env`를 자동으로
읽어주는 `spring-dotenv` 라이브러리가 Spring Boot 4.1에서 작동하지 않아서(`spring.factories` 기반
`SpringApplicationRunListener` 등록 방식이 이 버전과 안 맞는 것으로 보임), `app.jwt.secret=${JWT_SECRET}`
같은 필수 플레이스홀더가 해석되지 않아 부팅이 실패했다. 그래서 `dev` 타깃에 `.env`를 셸에서 직접
`source`해서 환경변수로 export하는 단계(`set -a && . ./.env && set +a`)를 추가해 우회했다.
`src/test/resources/application.properties`의 관련 주석도 이제 사실과 맞지 않아 정정했다.

수정 후 40개 동시 요청으로 재검증: `http-nio-5001-exec-*` 스레드가 정확히 **10개**로 캡되는 것을
확인했다. 이제 `make dev`로 로컬에서 재는 수치가 실제 배포 설정(threads.max=10)을 정확히 반영한다.

**✅ 값 상향까지 완료**: 위에서 측정 파이프라인 자체를 고친 뒤, 실제로
`server.tomcat.threads.max=10 → 100`, `threads.min-spare=1 → 5`, `accept-count=10 → 300`,
`max-connections=50 → 1000`으로 올렸다(로컬 14코어 기준, Mongo 드라이버 기본 커넥션 풀 한도인
100에 맞춰 threads.max를 잡았다). N+1 제거(3번 항목)와 합쳐서 재측정한 결과
(`PERFORMANCE_MEASUREMENTS.md` Baseline #3), 2,500명 스파이크에서 **실패율 90.4% → 0%**,
p99 3,678ms → 34.8ms로 완전히 해소됐다. N+1만 고쳤을 때는 요청은 빨라져도 연결 자체가
거부돼서 생존율이 그대로였는데(90.0%), "연결을 받아줄 슬롯"까지 늘리니 두 수정의 효과가 합쳐졌다.

---

### 2. `messages` 컬렉션에 인덱스가 전혀 없음

`src/main/java/com/ktb/chatapp/model/Message.java` 전체를 보면 `@Indexed`/`@CompoundIndex`가
하나도 없다. (`RateLimit`, `User`, `Session` 모델에는 있는데 `Message`, `Room`에는 없다.)

`spring.data.mongodb.auto-index-creation=true`가 켜져 있어도, 애초에 `@Indexed` 애노테이션이 없으면
인덱스가 생기지 않는다. 그 결과:

- `MessageRepository.findByRoomIdAndTimestampBefore(roomId, before, pageable)` — 방 히스토리 스크롤,
  방 목록 최근 메시지 카운트에 쓰이는 핵심 쿼리인데 `{room, timestamp}` 복합 인덱스가 없어 컬렉션 풀스캔 +
  인메모리 정렬을 한다. 메시지가 쌓일수록(부하테스트가 길어질수록) 점점 느려지다가 MongoDB의
  32MB 인메모리 정렬 한도에 걸려 예외가 날 수 있다.
- `MessageRepository.countRecentMessagesByRoomId(roomId, since)` (아래 3번 참고) — 매 메시지 전송마다
  풀스캔에 가까운 COUNT 쿼리를 실행한다.

**조치**: `Message`에 최소`@CompoundIndex(def = "{'room': 1, 'timestamp': -1}")` 추가.

**✅ 수정됨** — `Message.java`에 `{room: 1, timestamp: -1}` 복합 인덱스 추가(3번 항목 수정과 함께
적용). `countRecentMessagesByRoomId`/`findByRoomIdAndTimestampBefore`가 이 인덱스를 타게 된다.

---

### 3. `GET /api/rooms` — N+1 쿼리 폭발 + 페이지네이션 무의미

`src/main/java/com/ktb/chatapp/service/RoomService.java:36-64`, `184-198`

```java
List<RoomResponse> roomResponses = roomRepository.findAll().stream()   // 전체 방 로드, 페이지네이션 없음
    .map(room -> mapToRoomResponse(room, name))
    ...
```

```java
private RoomResponse mapToRoomResponse(Room room, String name) {
    ...
    creator = userRepository.findById(room.getCreator()).orElse(null);      // 방 1개당 쿼리 1회
    List<User> participants = room.getParticipantIds().stream()
        .map(userRepository::findById)                                     // 참가자 수만큼 쿼리
        ...
    int recentMessageCount = recentMessageCounter.countRecentMessages(room.getId()); // 방 1개당 COUNT 쿼리 1회
    ...
}
```

`roomRepository.findAll()`로 방 전체를 무조건 다 불러오고(응답 DTO에 `page`, `pageSize`, `totalPages`
필드가 있지만 실제로는 `pageSize = 전체 방 개수`, `totalPages = 1`로 고정 — **페이지네이션이 장식용**),
방 하나마다 creator 조회 1회 + 참가자 수만큼 조회 + 최근 메시지 COUNT 쿼리 1회를 순차 실행한다.

`ramp-up-test.js` 시나리오 기준(~100-250개 방, 방당 2-5명)이면 방 목록 조회 **한 번**에 최소
`250 × (1 + 3 + 1) ≈ 1,250`회의 순차 MongoDB 왕복이 발생한다. 이 엔드포인트는 프론트엔드 방 목록
페이지가 로드/새로고침될 때마다, 그리고 `@RateLimit`이 걸려 있을 만큼(`RoomController.java:98`)
자주 호출되는 엔드포인트다. **전체 코드베이스에서 가장 심각한 병목**으로 판단된다.

같은 `mapToRoomResponse`가 `createRoom`(`RoomService.java:132`)과 `joinRoom`(`RoomService.java:172`)의
이벤트 페이로드 생성에도 재사용되어, 방 생성/입장 같은 쓰기 경로에도 같은 비용이 붙는다.

**조치**: `findAll()` 대신 실제 페이지네이션 적용, creator/participants는 `userRepository.findAllById(...)`로
배치 조회, `recentMessageCount`는 `$facet`/집계 파이프라인으로 한 번에 계산하거나 캐시.

**✅ N+1 부분 수정됨** (페이지네이션은 아직 미적용) — creator/participants를
`userRepository.findAllById(...)`로 방 전체를 통틀어 한 번만 배치 조회하도록 바꾸고,
`recentMessageCount`도 `MessageRepository.countRecentMessagesByRoomIds`(`$group` 집계, 방
여러 개를 한 쿼리로) 로 바꿨다. 실측 결과(`PERFORMANCE_MEASUREMENTS.md` Baseline #2)로는 완료된
요청의 지연시간이 13배 빨라졌다(스파이크 기준 539ms → 41ms). **다만 스파이크 생존율은 90.4% →
90.0%로 거의 안 바뀌었다** — `accept-count=10`/`max-connections=50`을 넘는 순간 유입은 애플리케이션
코드 속도와 무관하게 연결 단계에서 거부되기 때문이다. 즉 1번 항목(스레드 풀)을 먼저/같이 고쳐야
이 N+1 수정의 효과가 생존율에도 드러난다. `findAll()` 기반 페이지네이션 미적용 문제는 여전히
남아있다.

---

### 4. 채팅 메시지 1건당 MongoDB 왕복 9~10회 (전부 동기 블로킹)

`src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`

`handleChatMessage` 한 번 호출(`chatMessage` 이벤트 하나) 안에서 발생하는 순차 DB 호출을 추적하면:

| 줄 | 호출 | Mongo 왕복 |
|---|------|-----------|
| 81 | `sessionService.validateSession(...)` | find + save (lastActivity 갱신) = 2회 |
| 94 | `rateLimitService.checkRateLimit(...)` | find + save = 2회 |
| 113 | `userRepository.findById(socketUser.id())` | 1회 |
| 125 | `roomRepository.findById(roomId)` | 1회 |
| 164 | `messageRepository.save(message)` | 1회 |
| 171 | `roomActivityNotifier.notifyMessageStored(roomId)` → `countRecentMessagesByRoomId` | 1회(무인덱스, 2번 항목 참고) |
| 176 | `sessionService.updateLastActivity(socketUser.id())` | find + save = 2회 |

**176번 줄은 81번 줄과 완전히 중복이다.** `validateSession` 내부에서 이미 `lastActivity`를 갱신하고
저장하는데(`SessionService.java:70-108`), 메시지 처리 끝에서 `updateLastActivity`를 한 번 더 호출해
같은 세션 도큐먼트를 또 find+save 한다 — 메시지 1건당 세션 관련 Mongo 왕복만 4회.

합치면 텍스트 메시지 1건에 **최소 9회**(파일 메시지는 `fileRepository.findById` 추가로 10~11회)의
순차 blocking MongoDB 호출이 발생하고, 이게 전부 Netty-socketio 워커 스레드 위에서 동기적으로
실행된다(`@Async`/전용 executor 전무 — 코드베이스 전체에 `@EnableAsync` 없음). 동시 메시지 처리량은
사실상 "워커 스레드 수 ÷ (메시지당 총 DB 지연시간)"으로 수렴한다.

**조치**:
- 176번 줄의 중복 `updateLastActivity` 호출 제거 (즉시 적용 가능, 리스크 없음).
- rate limit/세션 검증을 Redis 기반으로 교체(아래 "Redis 미사용" 항목 참고) 해 라운드트립 자체를 없애기.
- `roomActivityNotifier.notifyMessageStored`를 동기 카운트 쿼리 대신 인메모리 카운터/캐시로 교체.

---

### 5. 금칙어 필터: 메시지 1건마다 10,000개 단어 naive 스캔

`src/main/java/com/ktb/chatapp/util/BannedWordChecker.java:24-29`

```java
public boolean containsBannedWord(String message) {
    ...
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return bannedWords.stream().anyMatch(normalizedMessage::contains);
}
```

`fake_banned_words_10k.txt`는 실제로 10,000줄이다(`wc -l` 확인). 메시지 하나가 들어올 때마다 최악의 경우
10,000번의 `String.contains()` 부분 문자열 검색을 순차 실행한다 — 전형적인 O(사전 크기 × 메시지 길이)
CPU 바운드 핫패스. `ChatMessageHandler.handleChatMessage` 내부(141번 줄)에서 메시지마다 무조건 호출되므로,
메시지 처리량이 늘어날수록 CPU가 이 필터에 잡아먹힌다. 이미 스레드 풀이 작은 상황(1, 4번 항목)에서
CPU까지 여기서 소모되면 전체 처리량이 더 떨어진다.

**조치**: Aho-Corasick 같은 멀티패턴 매칭 알고리즘으로 교체(예: `org.ahocorasick:ahocorasick`) —
사전 크기와 무관하게 메시지 길이에 비례하는 O(n) 매칭이 가능하다.

---

## High

### 6. 히스토리 스크롤(`fetchPreviousMessages`)도 N+1

`src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java:66-76`

```java
List<MessageResponse> messageResponses = sortedMessages.stream()
        .map(message -> {
            var user = findUserById(message.getSenderId());   // 메시지마다 findById 1회
            return messageResponseMapper.mapToMessageResponse(message, user);
        })
        .collect(Collectors.toList());
```

한 번에 최대 30건(`BATCH_SIZE`)을 불러오면서, 발신자 정보를 메시지마다 개별 `findById`로 조회한다.
같은 사람이 연속으로 여러 메시지를 보냈어도 매번 새로 조회한다. `userRepository.findAllById(...)`로
배치 조회 + 중복 제거하면 최대 30회 쿼리를 1회로 줄일 수 있다.

### 7. 읽음 처리(`markMessagesAsRead`, 스크롤 시 읽음 갱신) — 가장 자주 발동되는 N+1

`src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java:39-53`

```java
for (String messageId : messageIds) {
    var messageOptional = messageRepository.findById(messageId);   // 메시지 ID당 find
    ...
    messageRepository.save(message);                               // 메시지 ID당 save
}
```

메시지 ID 목록을 받아 **하나씩** find + save를 반복한다(벌크 업데이트 미사용). 이 메서드는 두 경로에서
호출된다:

- `MessageLoader`의 히스토리 스크롤 (최대 30건 → 최대 60회 왕복)
- `MessageReadHandler.handleMarkAsRead` (`markMessagesAsRead` 소켓 이벤트) — `loadtest/README.md`에
  명시된 대로 "message → markMessagesAsRead → messagesRead" 흐름으로 **거의 모든 메시지마다** 클라이언트가
  쏘는 이벤트다. 게다가 `MessageReadHandler.java:52-63`에서 이 호출 전에 이미 `messageRepository.findById`
  (roomId 확인용) + `userRepository.findById` + `roomRepository.findById`를 추가로 하므로, 읽음 처리
  이벤트 하나가 메시지 ID 개수에 비례한 쿼리 폭탄이 된다.

**조치**: MongoDB의 `updateMany`/bulk write로 한 번에 처리 (`$addToSet`으로 readers 배열에 추가하되
"이미 읽음" 체크는 쿼리 필터(`readers.userId: {$ne: userId}`)로 대체).

---

## Medium

### 8. Redis가 인프라엔 있는데 코드에서 전혀 안 쓰인다

`docker-compose.yaml`에 Redis 컨테이너가 있고, `pom.xml`에 `spring-boot-starter-data-redis`,
`redisson`이 의존성으로 들어 있고, `.env.template`에 `REDIS_HOST`/`REDIS_PORT`가 필수 환경변수로
요구되는데, 실제 Java 코드에서 `RedisTemplate`/`Redisson` 등을 참조하는 곳이 **한 군데도 없다**
(`grep -rl "Redis\|redisson\|RedisTemplate" src/main/java` 결과 0건). 세션(`SessionMongoStore`)과
Rate Limit(`RateLimitMongoStore`) 모두 MongoDB를 쓴다 — 백엔드 README에도 "MongoDB TTL 기반
세션·레이트리밋"이라고 명시돼 있다.

세션 검증(모든 REST 요청 + 모든 소켓 이벤트)과 rate limit 체크(4번 항목)가 전부 MongoDB 왕복인데,
이미 띄워져 있는 Redis를 전혀 활용하지 않고 있다. 이 두 가지를 Redis로 옮기면(특히 rate limit은
Redis `INCR` + `EXPIRE` 한 번으로 원자적 처리 가능) 4번 항목의 병목을 근본적으로 줄일 수 있다.

### 9. `RateLimitService.checkRateLimit`의 `@Transactional`은 사실상 무의미하고, 원자성도 없다

`src/main/java/com/ktb/chatapp/service/RateLimitService.java:39`

```java
@Transactional
public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
```

코드베이스 전체에 `MongoTransactionManager` 빈 정의가 없고(`grep` 결과 0건), `docker-compose.yaml`의
Mongo도 `--replSet` 없는 standalone이라 애초에 MongoDB 트랜잭션을 못 쓴다. 실제로 로그인 후
`GET /api/rooms`(rate limit 적용 엔드포인트)를 호출해 확인해보니 정상적으로 200을 반환했다 — 즉
`@Transactional`이 예외 없이 조용히 아무 효과도 없는 상태로 무시되고 있는 것으로 보인다(트랜잭션
매니저 빈이 없으면 Spring이 해당 애노테이션을 no-op으로 넘긴다).

즉 `find → count 비교 → save`로 이어지는 read-then-write 로직에 실질적인 원자성 보장이 없다.
같은 클라이언트가 여러 요청을 동시에 보내면(동일 유저가 여러 탭/여러 소켓 이벤트를 거의 동시에
쏘는 상황) 두 요청이 같은 카운트를 읽고 각자 +1 해서 저장하는 lost-update가 가능해 rate limit이
과소 집계될 수 있다. 처리량 자체보다는 정확성 문제지만, "제한이 걸려야 하는데 안 걸림"으로
부하테스트 결과 해석에 혼선을 줄 수 있어 기록해둔다.

**조치**: 8번과 묶어서 Redis `INCR`/Lua 스크립트 기반의 원자적 rate limiter로 교체.

### 10. Socket.IO 서버 설정

`src/main/java/com/ktb/chatapp/config/SocketIOConfig.java`

- `socketConfig.setAcceptBackLog(10)` (49번 줄) — TCP accept 대기열이 10. `ramp-up-test.js`처럼
  짧은 시간에 다수의 소켓 연결이 몰리는 시나리오에서 연결 자체가 거부될 수 있다.
- `socketConfig.setTcpNoDelay(false)` (48번 줄) — Nagle 알고리즘이 켜져 있다. 채팅처럼 작은
  메시지를 자주 보내는 프로토콜에는 보통 `true`(Nagle 비활성화)를 권장한다 — 지연시간 측정
  (`load-test.js`의 message latency 지표)에 영향을 줄 수 있다.
- `MemoryStoreFactory()` 사용(72번 줄, 주석에 "단일노드 전용"이라고 명시) — 백엔드를 여러 인스턴스로
  수평 확장하면 룸 멤버십/커넥션 상태가 인스턴스별로 분리되어 브로드캐스트가 깨진다. 부하테스트
  대응으로 인스턴스를 늘릴 계획이라면 이 부분부터 막힌다.

### 11. JVM 힙 기본값이 작다

`apps/backend/Makefile:14` (`JVM_OPTS ?= -Xmx1024m`), 배포 스크립트(`app-control.sh`)도 이 값을
그대로 사용한다. 위에서 나온 대로 메시지 1건 처리마다 여러 개의 응답/DTO 객체가 생성되는 구조라
GC 압박이 상당할 수 있다. 부하테스트 서버 스펙에 맞춰 힙 크기를 올리고, GC 로그(`-Xlog:gc`)를 켜서
Grafana의 JVM 대시보드(`monitoring/grafana/provisioning/dashboards/spring-boot-app-dashboard.json`)로
GC pause time을 같이 관찰하는 걸 권장한다.

### 12. 파일 업로드가 이미 스레드가 부족한 Tomcat 위에서 동기 디스크 I/O

`LocalStorage`/`FileService`가 로컬 디스크에 동기적으로 파일을 쓴다. 1번 항목(Tomcat 스레드 10개)과
겹치면 파일 업로드 요청 하나가 스레드를 오래 붙잡아 다른 REST 요청까지 같이 지연될 수 있다.
부하테스트 시나리오에 파일 업로드가 포함된다면(현재 `e2e/artillery`에는 있고 `loadtest/`
Node 스크립트에는 없음) 우선순위를 올릴 것.

---

## 우선순위 로드맵 제안

빠르고 리스크 낮은 것부터:

1. **`server.tomcat.threads.max`/`accept-count`/`max-connections` 상향** — 설정값 하나, 배포만 하면 즉시 효과.
2. **`ChatMessageHandler.java:176`의 중복 `updateLastActivity` 제거** — 코드 한 줄 삭제, 메시지당 Mongo 왕복 2회 감소.
3. **`Message`에 `{room:1, timestamp:-1}` 복합 인덱스 추가** — 모델에 애노테이션 한 줄, 컬렉션 재시작 시 자동 생성.
4. **`RoomService.getAllRooms`/`mapToRoomResponse` N+1 제거** — `findAllById` 배치 조회 + 실제 페이지네이션. 가장 손이 많이 가지만 임팩트도 제일 크다.
5. **`MessageReadStatusService.updateReadStatus`를 벌크 업데이트로 교체** — `markMessagesAsRead`가 로드테스트에서 가장 자주 발동되는 이벤트라 우선순위 높음.
6. **금칙어 필터를 Aho-Corasick으로 교체** — CPU 프로파일링(Grafana `process_cpu_usage`, `system_cpu_usage` 패널)에서 이 부분이 실제로 얼마나 잡아먹는지 먼저 확인 후 착수 추천.
7. **세션/rate limit을 Redis로 이전** — 이미 떠 있는 인프라를 활용하는 구조적 개선. 임팩트는 크지만 범위가 넓어 후순위.
8. **Socket.IO `acceptBackLog`/`tcpNoDelay` 튜닝** — 연결 폭주 시나리오(`ramp-up-test.js`) 대비.

각 항목을 고친 뒤에는 `loadtest/README.md`가 안내하는 대로 같은 시나리오로 baseline 대비 처리량/지연시간을
비교하고, `apps/backend/monitoring/grafana/provisioning/dashboards/spring-boot-app-dashboard.json`
대시보드(HTTP Request Rate/Latency, JVM Heap, GC Pause Time)로 실제 개선 여부를 확인할 것을 권장한다.
