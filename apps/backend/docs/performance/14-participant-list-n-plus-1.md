# 14. 방 입장·퇴장마다 참가자 목록 조회 N+1

> **한줄 요약**: 방에 입장하거나 퇴장할 때마다 참가자 수만큼 개별 `findById`를 반복했다.
> `findAllById` 배치 조회로 교체 — 순차 입장/퇴장 기준 joinRoom p50 21ms → 14ms(-33%),
> leaveRoom p50 22ms → 11ms(-50%). 동시 30명 부하로 재보면 join은 p50 -37%/p95 -25%로
> 여전히 뚜렷하지만, leave는 p50만 -20%고 p95는 거의 그대로다(아래 "강한 부하" 절 참고 —
> 참가자 브로드캐스트 팬아웃 비용이 다음 병목일 가능성).

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 방 입장/퇴장 이벤트(재접속이 몰리면 특히 자주 발생) | Critical |

## 문제

[3번](03-get-rooms-n-plus-1.md)·[6번](06-message-loader-n-plus-1.md) 항목을 고치면서 나머지 코드를
훑다가 똑같은 패턴이 두 군데 더 남아있는 걸 발견했다 — `RoomJoinHandler`와 `RoomLeaveHandler`가
각자 독립적으로 똑같은 코드를 갖고 있었다.

**`RoomJoinHandler.java`(수정 전, 방 입장 시 매번)**:
```java
List<UserResponse> participants = roomOpt.get().getParticipantIds()
        .stream()
        .map(userRepository::findById)   // 참가자 수만큼 findById
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(UserResponse::from)
        .toList();
```

**`RoomLeaveHandler.java`(수정 전, `broadcastParticipantList`, 방 퇴장 시 매번)**:
```java
var participantList = roomOpt.get()
        .getParticipantIds()
        .stream()
        .map(userRepository::findById)   // 여기도 똑같이
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(UserResponse::from)
        .toList();
```

누가 방에 입장하거나 퇴장할 때마다, 그 방의 참가자 수만큼 개별 `findById`가 순차로 나갔다. 방
하나에 참가자가 많을수록 이벤트 하나당 쿼리가 그만큼 늘어나는 전형적인 N+1이다. [13번
항목](13-reconnect-leave.md)(재접속이 방 나가기로 처리되는 문제)이 아직 보류 상태라, 재접속이
몰리는 스파이크 구간에 이 N+1이 join/leave 양쪽에서 동시에 반복 발동될 수 있는 조합이었다.

## 조치 ✅

두 핸들러가 완전히 같은 로직을 중복으로 갖고 있었기 때문에, `userRepository.findAllById(...)` +
`Map` 캐시 배치 조회 로직을 `ParticipantListMapper`라는 공용 컴포넌트 하나로 통합하고 양쪽에서
재사용하도록 했다:

```java
@Component
@RequiredArgsConstructor
public class ParticipantListMapper {

    private final UserRepository userRepository;

    public List<UserResponse> toParticipantList(Room room) {
        Map<String, User> userCache = new HashMap<>();
        for (User user : userRepository.findAllById(room.getParticipantIds())) {
            userCache.put(user.getId(), user);
        }
        return room.getParticipantIds().stream()
                .map(userCache::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();
    }
}
```

`RoomJoinHandler`/`RoomLeaveHandler`는 이제 각자의 로직에서 이 한 줄만 호출한다:
```java
List<UserResponse> participants = participantListMapper.toParticipantList(roomOpt.get());
```

기존 유닛테스트(`RoomJoinHandlerTest`, `RoomLeaveHandlerTest`)도 새 협력 객체를 목으로 주입하도록
갱신해서 통과를 확인했다(4/4).

**주의**: 입장/퇴장을 트리거하는 조건(재접속을 방 나가기로 처리하는 로직, 13번 항목)은 이번
수정 범위가 아니다 — 참가자 목록을 "어떻게 조회하는지"만 바꿨고, "언제 join/leave가
발동되는지"는 그대로 뒀다.

## 측정 방법

스크립트: `apps/backend/perf-artillery/participant-list-load.js` (git 미추적).

**시나리오**: 유저 30명을 미리 방 1개에 채워 넣은 뒤(참가자 목록을 크게 만들어 N+1이 뚜렷하게
드러나게), 별도 유저 5명이 순서대로 그 방에 입장(`joinRoomSuccess`까지)했다가 퇴장
(`participantsUpdate`까지)하는 왕복시간을 각각 측정한다.

`RoomLeaveHandler`는 `client.leaveRoom()`으로 나가는 소켓 본인을 방에서 먼저 뺀 뒤에
`participantsUpdate`를 브로드캐스트하므로, **나가는 소켓 본인은 그 이벤트를 못 받는다** — 방에
남아있는 다른 소켓(필러 유저)에서 관측해야 한다. 처음엔 이걸 놓쳐서 나가는 소켓 자신에게서
관측하다가 "수정 전후 차이가 거의 없다"는 잘못된 결과가 나왔고, 원인을 찾아 스크립트를
고쳤다 — 실제로는 필러 소켓이 이전 join 브로드캐스트의 잔여 이벤트를 잡지 않도록 join 이후
500ms를 흘려보내고 나서 측정을 시작한다.

### 재현 명령

```bash
cd apps/backend && make dev

cd apps/backend/perf-artillery
NODE_PATH=<repo-root>/loadtest/node_modules node participant-list-load.js --participants=30 --trials=5
```

## 측정 결과

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| joinRoom 왕복 p50 | 21ms | **14ms (-33%)** |
| joinRoom max | 25ms | 17ms |
| leaveRoom 왕복 p50 | 22ms | **11ms (-50%)** |
| leaveRoom max | 42ms | 18ms |

수정 후 상태로 한 번 더 반복 측정(joinRoom p50 15ms, leaveRoom p50 11ms)해서 일관성을
확인했다.

**해석**: 참가자 30명 기준으로 왕복 지연시간이 join은 약 1/3, leave는 약 1/2 줄었다. 이 항목도
[6번](06-message-loader-n-plus-1.md)처럼 로컬 단일 Mongo 컨테이너 기준이라 절대 절감폭이 몇~십
ms 수준이지만, 방 참가자 수가 늘어날수록(대회 시나리오처럼 방 하나에 사람이 많이 몰리는 경우)
개별 조회 횟수가 그만큼 늘어나는 구조였으므로 참가자 수가 큰 방일수록 효과가 커진다.

## 강한 부하(동시 요청) 측정 — p95/p99

위 측정은 유저 5명이 순서대로 하나씩 입장/퇴장하는 가벼운 시나리오였다. 실제 스파이크에 더
가까운 "여러 명이 한꺼번에" 시나리오에서 p95/p99까지 재봤다.

### 측정 방법

스크립트: `apps/backend/perf-artillery/participant-list-load-concurrent.js` (git 미추적).

**시나리오**: 배경 참가자 50명을 방에 미리 채운 뒤, 별도 유저 30명이 **동시에** 방에 입장하고
(각자 `joinRoomSuccess` 왕복시간 측정 — 요청자 소켓에 직접 응답이 오므로 동시에 쏴도 유저별로
정확히 상관관계가 잡힌다), 그 30명이 다시 **동시에** 방을 퇴장한다.

퇴장 측정은 까다롭다 — `RoomLeaveHandler`가 나가는 소켓 본인을 방에서 먼저 뺀 뒤 브로드캐스트
하므로 본인은 응답을 못 받는다. 그래서 방에 남아있는 관찰자 소켓이 매
`participantsUpdate`(참가자 전체 스냅샷)를 이전 스냅샷과 diff해서 "누가 빠졌는지"를 알아내고,
그 유저가 `leaveRoom`을 emit한 시각과 비교해 지연시간을 계산했다 — 30명이 동시에 나가도 정확히
누구의 퇴장인지 스냅샷 diff로 맞출 수 있다(순서 가정에 의존하지 않음).

### 재현 명령

```bash
cd apps/backend && make dev

cd apps/backend/perf-artillery
NODE_PATH=<repo-root>/loadtest/node_modules node participant-list-load-concurrent.js --fill=50 --concurrent=30
```

### 측정 결과 (각 3회 반복)

**joinRoom(동시 30명)** — 뚜렷하고 일관된 개선

| 지표 | 수정 전 (3회) | 수정 후 (3회) |
|---|---|---|
| p50 | 122ms, 113ms, 101ms | 69ms, 80ms, 64ms |
| p95 | 169ms, 168ms, 151ms | 113ms, 131ms, 124ms |
| p99 | 170ms, 170ms, 151ms | 119ms, 137ms, 127ms |

평균으로 보면 **p50 ~112ms → ~71ms(-37%), p95 ~163ms → ~123ms(-25%)**.

**leaveRoom(동시 30명)** — 개선폭이 작고 실행마다 편차가 큼

| 지표 | 수정 전 (3회) | 수정 후 (3회) |
|---|---|---|
| p50 | 40ms, 70ms, 72ms | 45ms, 49ms, 52ms |
| p95 | 68ms, 87ms, 93ms | 77ms, 84ms, 82ms |
| p99 | 68ms, 87ms, 93ms | 77ms, 84ms, 82ms |

평균으로 보면 **p50 ~61ms → ~49ms(-20%)**지만 **p95는 ~83ms → ~81ms로 사실상 그대로**다.

**해석 — join과 leave가 왜 다르게 나오나**: join 이벤트는 참가자 목록 조회 외에도
`messageLoader.loadMessages`(메시지 30건 로드, [6번 항목](06-message-loader-n-plus-1.md)에서 이미
배치 조회로 고쳐둔 부분)를 같이 수행하는데, 이 두 개선이 겹치면서 join 쪽 효과가 더 크고
뚜렷하게 나온 것으로 보인다. leave는 참가자 목록 조회 하나만 고쳤는데, 동시 30명이 몰리면
`socketIOServer.getRoomOperations(roomId).sendEvent(...)`(참가자 스냅샷을 남은 ~50~80명 전원에게
브로드캐스트)가 이벤트 하나당 최대 80회씩 발생한다 — 이 팬아웃 비용이 이번에 줄인 개별
`findById` 조회 비용보다 커서, N+1 제거 효과가 노이즈에 가려진 것으로 판단된다. 즉 **동시
부하에서는 참가자 목록 브로드캐스트 자체의 팬아웃 비용이 다음 병목**일 가능성이 있다 — 정확히
검증하려면 별도 측정이 필요하다.
