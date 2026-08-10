# 2. `messages` 컬렉션에 인덱스가 전혀 없음

> **한줄 요약**: `Message` 모델에 인덱스가 하나도 없어서 방 히스토리 조회·메시지 카운트가 매번 컬렉션 풀스캔이었다. `{room, timestamp}` 복합 인덱스 추가로 해결.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 메시지 전송, 방 목록, 히스토리 스크롤, 읽음 처리 전부 | Critical |

## 문제

`src/main/java/com/ktb/chatapp/model/Message.java`에는 `@Indexed`/`@CompoundIndex`가 하나도
없었다. (`RateLimit`, `User`, `Session` 모델에는 있는데 `Message`, `Room`에는 없었다.)

`spring.data.mongodb.auto-index-creation=true`가 켜져 있어도, 애초에 `@Indexed` 애노테이션이
없으면 인덱스가 생기지 않는다. 그 결과 두 곳이 느렸다:

- `MessageRepository.findByRoomIdAndTimestampBefore(roomId, before, pageable)` — 방 히스토리
  스크롤과 방 목록 최근 메시지 카운트의 핵심 쿼리인데, `{room, timestamp}` 복합 인덱스가 없어서
  컬렉션 풀스캔 + 인메모리 정렬을 했다. 메시지가 쌓일수록(부하테스트가 길어질수록) 점점
  느려지다가 MongoDB의 32MB 인메모리 정렬 한도에 걸려 예외가 날 수 있는 구조였다.
- `MessageRepository.countRecentMessagesByRoomId(roomId, since)`([03번 항목](03-get-rooms-n-plus-1.md) 참고) —
  매 메시지 전송마다 풀스캔에 가까운 COUNT 쿼리를 실행했다.

## 조치 ✅

`Message`에 복합 인덱스를 추가했다([03번 항목](03-get-rooms-n-plus-1.md) 수정과 함께 적용):

```java
@CompoundIndexes({
    @CompoundIndex(name = "room_timestamp_idx", def = "{'room': 1, 'timestamp': -1}")
})
public class Message { ... }
```

`countRecentMessagesByRoomId`/`findByRoomIdAndTimestampBefore`가 이제 이 인덱스를 탄다.

## 측정 결과

이 항목은 [03번(N+1 제거)](03-get-rooms-n-plus-1.md)와 같은 코드 변경으로 함께 적용해서 따로
분리 측정하지 않았다. 두 항목의 합산 효과는 [03번 문서](03-get-rooms-n-plus-1.md)의 Baseline
#1→#2 결과 참고.
