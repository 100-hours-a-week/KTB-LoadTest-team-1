# 6. 히스토리 스크롤(`fetchPreviousMessages`)도 N+1

> **한줄 요약**: 메시지 30건 불러올 때 발신자 정보를 30번 따로 조회했다. `findAllById` 배치
> 조회로 교체 — 발신자 30명(전부 다른 유저) 기준 p50 10ms → 6ms.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 방 히스토리 스크롤 | High |

## 문제

`src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java:66-76`(수정 전)

```java
List<MessageResponse> messageResponses = sortedMessages.stream()
        .map(message -> {
            var user = findUserById(message.getSenderId());   // 메시지마다 findById 1회
            return messageResponseMapper.mapToMessageResponse(message, user);
        })
        .collect(Collectors.toList());
```

한 번에 최대 30건(`BATCH_SIZE`)을 불러오면서, 발신자 정보를 메시지마다 개별 `findById`로
조회했다. 같은 사람이 연속으로 여러 메시지를 보냈어도 매번 새로 조회하는 구조였다.

## 조치 ✅

`userRepository.findAllById(...)`로 배치 조회 + `Map` 캐시로 교체했다. [3번 항목](03-get-rooms-n-plus-1.md)
(`RoomService`/`RoomController` N+1 수정)에서 이미 쓴 것과 같은 패턴이다.

```java
Set<String> senderIds = sortedMessages.stream()
        .map(Message::getSenderId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
Map<String, User> userCache = new HashMap<>();
for (User user : userRepository.findAllById(senderIds)) {
    userCache.put(user.getId(), user);
}

List<MessageResponse> messageResponses = sortedMessages.stream()
        .map(message -> {
            User user = message.getSenderId() != null ? userCache.get(message.getSenderId()) : null;
            return messageResponseMapper.mapToMessageResponse(message, user);
        })
        .collect(Collectors.toList());
```

`senderId`가 null인 메시지(AI/시스템 메시지)는 이전과 동일하게 `sender: null`로 처리된다 —
동작 자체는 그대로고 조회 방식만 바뀌었다.

## 측정 방법

스크립트: `apps/backend/perf-artillery/message-loader-load.js` (git 미추적).

**시나리오**: 서로 다른 유저 30명이 방 1개에 각자 메시지를 1건씩 보낸다(=발신자가 전부 다른
메시지 30건짜리 히스토리를 만든다 — N+1이 가장 뚜렷하게 드러나는 조건). 그 뒤
`fetchPreviousMessages`를 5회 반복 호출해서 emit부터 `previousMessagesLoaded` 응답까지의
왕복시간을 잰다.

기능 정확성은 별도 스크립트 `verify-message-loader-fix.js`로 확인했다 — 보낸 메시지 5건이
전부 조회되고, 각 메시지의 `sender` 정보(이름)가 정확히 채워지는지 검증 통과.

### 재현 명령

```bash
cd apps/backend && make dev

cd apps/backend/perf-artillery
NODE_PATH=<repo-root>/loadtest/node_modules node message-loader-load.js --senders=30 --trials=5
```

## 측정 결과

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| p50 | 10ms | **6ms (-40%)** |
| max | 18ms | **8ms (-56%)** |

**해석**: 방향은 확실하지만 절대적인 절감폭은 [4](04-chatmessage-duplicate-session.md)·
[7번 항목](07-mark-as-read-bulk-update.md)만큼 크지 않다. 로컬 단일 Mongo 컨테이너 기준 왕복
1회가 워낙 저렴해서(대략 0.3ms 안팎), 30회를 1회로 줄여도 절대 시간으로는 몇 ms 차이다. 다만
쿼리 "횟수" 자체는 30회 → 1회로 줄었으므로, 배포 환경처럼 DB와의 네트워크 거리가 있는 경우나
DB 자체가 부하를 받고 있는 상황에서는 이 차이가 훨씬 크게 벌어질 것으로 예상된다.

## 남은 일

이번 수정은 `MessageLoader`의 발신자 조회만 다룬다. 같은 파일 안에서 호출하는
`messageReadStatusService.updateReadStatus`([7번 항목](07-mark-as-read-bulk-update.md))는 이미
별도로 bulk update로 고쳐져 있다.
