# 6. 히스토리 스크롤(`fetchPreviousMessages`)도 N+1

> **한줄 요약**: 메시지 30건 불러올 때 발신자 정보를 30번 따로 조회한다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | 방 히스토리 스크롤 | High |

## 문제

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
같은 사람이 연속으로 여러 메시지를 보냈어도 매번 새로 조회한다.

## 조치 (미착수)

`userRepository.findAllById(...)`로 배치 조회 + 중복 제거하면 최대 30회 쿼리를 1회로 줄일 수 있다.
[3번 항목](03-get-rooms-n-plus-1.md)(`RoomService`/`RoomController` N+1 수정)에서 이미 쓴
`userRepository.findAllById` + `Map` 캐시 패턴을 그대로 재사용할 수 있다.
