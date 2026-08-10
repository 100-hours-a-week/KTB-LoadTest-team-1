# 3. `GET /api/rooms` — N+1 쿼리 폭발 + 페이지네이션 무의미

> **한줄 요약**: 방 목록 조회 1번에 방 개수 × 4배 가까운 순차 쿼리가 발생했다. 배치 조회로
> N+1은 없앴지만, `findAll()`이 방 전체를 무조건 다 불러오는 문제(가짜 페이지네이션)는 남아있다.
> N+1을 없애는 과정에서 새 버그를 하나 심었었는데, 그것도 잡았다(아래 "회귀 버그" 참고).

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ⚠️ N+1은 수정 완료, 페이지네이션은 미착수 | 방 목록 조회(가장 빈번한 엔드포인트) | Critical |

## 문제

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

무슨 일이 벌어지는지 정리하면:

- `roomRepository.findAll()`로 방 전체를 무조건 다 불러온다.
- 응답 DTO에 `page`, `pageSize`, `totalPages` 필드가 있지만 실제로는 `pageSize = 전체 방 개수`,
  `totalPages = 1`로 고정돼 있다 — **페이지네이션이 장식용**이다.
- 방 하나마다 creator 조회 1회 + 참가자 수만큼 조회 + 최근 메시지 COUNT 쿼리 1회를 순차 실행한다.

`ramp-up-test.js` 시나리오 기준(~100-250개 방, 방당 2-5명)으로 계산하면, 방 목록 조회 **한 번**에
최소 `250 × (1 + 3 + 1) ≈ 1,250`회의 순차 MongoDB 왕복이 발생한다.

이 엔드포인트는 두 가지 이유로 특히 아프다:

- 프론트엔드 방 목록 페이지가 로드/새로고침될 때마다 호출된다.
- `@RateLimit`이 걸려 있을 만큼(`RoomController.java:98`) 자주 호출되는 엔드포인트다.

**전체 코드베이스에서 가장 심각한 병목**으로 판단된다. 같은 `mapToRoomResponse`가
`createRoom`(`RoomService.java:132`)과 `joinRoom`(`RoomService.java:172`)의 이벤트 페이로드
생성에도 재사용되어, 방 생성/입장 같은 쓰기 경로에도 같은 비용이 붙는다.

> **참고**: `GET /api/rooms`가 방 전체를 참여 여부와 무관하게 다 돌려주는 것 자체는 버그가
> 아니라 의도된 설계로 보인다. `Room` 모델에 `hasPassword`/`password` 필드가 있어("입장할 때"
> 비밀번호를 요구하는 구조) 이미 참여한 방만 보여주는 "내 채팅방" 목록이 아니라, 누구나 둘러보고
> 원하는 방에 들어갈 수 있는 **공개 채팅 로비** 방식으로 설계된 것으로 보인다(프론트
> `useRoomList.js`도 "내 채팅방"이 아닌 범용 "채팅방 목록" 표현을 씀). 다만 코드 분석에 기반한
> 추론이라 100% 확신은 아니다.

## 조치

`findAll()` 대신 실제 페이지네이션 적용, creator/participants는 `userRepository.findAllById(...)`로
배치 조회, `recentMessageCount`는 집계 파이프라인으로 한 번에 계산.

### ✅ N+1 부분 수정 완료 (페이지네이션은 아직 미적용)

creator/participants를 `userRepository.findAllById(...)`로 방 전체를 통틀어 한 번만 배치 조회하도록
바꾸고, `recentMessageCount`도 방 여러 개를 한 번에 집계하는 쿼리로 바꿨다.
[02번 항목](02-message-index.md)의 `{room,timestamp}` 인덱스 추가와 같은 코드 변경에 함께 적용했다.

### ⚠️ 남은 문제 — 페이지네이션이 여전히 장식용

`roomRepository.findAll()`이 여전히 DB에 있는 방을 전부 불러온다. N+1을 배치 쿼리로 바꿔서
"쿼리 횟수"는 줄었지만, 그 배치 쿼리 자체(`findAllById`, 최근 메시지 집계)가 **방 개수에 비례해서
커진다.**

부하테스트가 길어져 방이 계속 생성되는 시나리오라면, 시간이 지날수록 이 엔드포인트 하나의
응답 크기와 처리 시간이 계속 늘어난다는 뜻이다. 아래 측정 결과는 "방 200개"라는 고정된 시드
규모에서만 유효하다 — 방 개수를 큰 폭으로 늘려서 다시 재보면 어느 지점부터 다시 느려지는지
드러날 것이다. **사용자 판단으로 이번 라운드는 보류.**

### 🐛 회귀 버그 — N+1 수정 자체가 이 엔드포인트를 깨뜨리고 있었다 (✅ 수정 완료)

N+1을 없애면서 `recentMessageCount`를 배치로 계산하려고 `RoomMessageCount`라는 프로젝션
인터페이스(`getId()`/`getCount()`)를 만들었는데, 이걸 리포지토리 `@Aggregation` 메서드의 반환
타입으로 쓰자 문제가 생겼다.

- **증상**: Spring Data MongoDB가 group 단계의 `_id`를 `getId()`에 제대로 바인딩하지 못했다
  (`@Field("_id")`를 붙여도 소용없었다 — 직접 검증). 그 결과 **모든 방의 `getId()`가 항상
  null**을 반환했다.
- **터지는 조건**: 방이 2개 이상 집계되는 순간(실서비스처럼 방이 여러 개 있으면 거의 항상)
  `Collectors.toMap(RoomMessageCount::getId, ...)`(`RecentMessageCounter.java:39`)에서
  `IllegalStateException: Duplicate key null`이 발생했다.
- **겉보기 증상**: `RoomService.getAllRooms()`가 이 예외를 캐치해서 `{success:false, data:[]}`를
  **HTTP 200으로** 반환했다. 즉 방 목록 화면이 매번 빈 목록으로 렌더링됐다 — 프론트 e2e 채점
  시나리오에서 "방 입장 API는 200으로 성공했는데 화면엔 방이 하나도 안 보여서 입장 버튼을 못
  찾고 타임아웃"으로 나타난 게 바로 이 버그였다(원인이 프론트가 아니라 백엔드였다).
- **왜 처음엔 안 터졌나**: 세션 초반엔 30분 이내에 메시지가 있는 방이 0~1개뿐이라 우연히 안
  터졌다. 이후 부하테스트를 반복하면서 방/메시지 데이터가 쌓이자(방 238개 기준) **항상**
  재현되는 상태가 됐다.

**조치**: 리포지토리 `@Aggregation` + 인터페이스 프로젝션 조합 자체를 버렸다. 대신
`RecentMessageCounter.countRecentMessages(Collection<String>)`에서 `MongoTemplate.aggregate(...)`로
raw `Document`를 직접 읽는다(`doc.getString("_id")`/`doc.getInteger("count")`) — 프레임워크의
프로젝션 바인딩을 아예 거치지 않으므로 이 문제가 원천적으로 발생할 수 없다. `RoomMessageCount.java`는
삭제했고, 관련 리포지토리 메서드도 제거했다(로직이 `RecentMessageCounter`로 이동).

**N+1을 없앤 "한 쿼리로 배치 조회한다"는 구조 자체는 그대로 유지된다** — 쿼리 방식만 바뀌었을 뿐
방 개수만큼 쿼리를 반복하는 패턴으로 되돌아간 게 아니다.

**검증**: 실제로 버그가 재현되던 데이터(방 238개)에 `GET /api/rooms`를 3회 연속 호출해서 매번
`success:true`, 방 238개 전부, `recentMessageCount` 정상 반영, 에러 로그 0건을 확인했다.

## 측정 결과

방법론은 [GET_ROOMS_METHODOLOGY.md](GET_ROOMS_METHODOLOGY.md) 참고. N+1 제거는 인덱스 추가
([02번 항목](02-message-index.md))와 같은 코드 변경으로 함께 적용해서, 아래 수치는 **두 항목의
합산 효과**다(Baseline #1 → #2).

### 스파이크 테스트 (2,500 VU/10초)

| 지표 | Baseline #1 (수정 전) | **Baseline #2 (N+1 제거 + 인덱스 후)** |
|---|---|---|
| 완료 | 244건 | 254건 |
| **실패(`ERR_SOCKET_TIMEOUT`)** | 2,296건 (90.4%) | **2,286건 (90.0%)** |
| p50(완료된 것만) | 539.2ms | **40.9ms** |
| p95 | 3,464.1ms | 3,828.5ms |
| p99 | 3,678.4ms | 3,905.8ms |
| max | 4,367ms | 3,913ms |

**해석**

- 완료된 요청의 지연시간은 13배 빨라졌다(539ms → 41ms) — N+1 제거 자체는 확실히 효과가 있다.
- 하지만 **생존율(실패율)은 90.4% → 90.0%로 거의 그대로다.** 2,500명이 순간적으로 몰리면
  `accept-count=10` + `max-connections=50`을 초과하는 연결은 애플리케이션 코드가 얼마나
  빨라졌든 상관없이 **연결 단계에서부터** 거부/타임아웃되기 때문이다.
- [01번 항목(스레드 풀/accept-count)](01-tomcat-thread-pool.md)이 이 정도 규모의 스파이크에서는
  여전히 지배적인 병목임이 확인됐다.

### 30 req/s 스무스 램프

| 지표 | Baseline #1 (수정 전) | **Baseline #2 (N+1 제거 + 인덱스 후)** |
|---|---|---|
| 완료 | 878건 | 914건 |
| **실패(`ERR_SOCKET_TIMEOUT`)** | 657건 (42.8%) | **621건 (40.5%)** |
| p50 | 1,107.9ms | **854.2ms** |
| p95 | 1,495.5ms | 963.1ms |
| p99 | 1,790.4ms | 1,002.4ms |
| max | 1,828ms | 1,082ms |

**해석**: 지연시간은 개선(1,108ms → 854ms, p95/p99는 더 크게 개선)됐지만 실패율은 42.8% → 40.5%로
소폭만 줄었다. 30 req/s 정도로도 이미 스레드 풀(10개) 용량을 넘어서는 큐잉이 걸리기 때문에, N+1
제거만으로는 이 부하를 완전히 못 버틴다 — 스레드 풀/accept-count를 같이 늘려야 실패율이 눈에
띄게 줄어든다([01번 항목](01-tomcat-thread-pool.md) 참고).
