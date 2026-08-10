# 7. 읽음 처리(`markMessagesAsRead`) — 가장 자주 발동되는 N+1

> **한줄 요약**: 메시지를 읽음 처리할 때 메시지 ID마다 find+save를 반복해서 왕복 횟수가 메시지
> 개수에 비례했다. bulk update 1건으로 바꿔서 "N에 비례" → "상수 시간"으로 만들었다
> (메시지 200건 기준 p50 240ms → 43ms, -82%).

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 읽음 처리(가장 자주 발동되는 이벤트) | High |

## 문제

`src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java:39-53`(수정 전)

```java
for (String messageId : messageIds) {
    var messageOptional = messageRepository.findById(messageId);   // 메시지 ID당 find
    ...
    messageRepository.save(message);                               // 메시지 ID당 save
}
```

메시지 ID 목록을 받아 **하나씩** find + save를 반복한다(벌크 업데이트 미사용). 메시지 ID N개를
읽음 처리하면 Mongo 왕복이 **2×N회** 발생한다.

이 메서드는 두 경로에서 호출된다:

- `MessageLoader`의 히스토리 스크롤 (최대 30건 → 최대 60회 왕복)
- `MessageReadHandler.handleMarkAsRead` (`markMessagesAsRead` 소켓 이벤트) — "message →
  markMessagesAsRead → messagesRead" 흐름으로 **거의 모든 메시지마다** 클라이언트가 쏘는
  이벤트다. 사용자가 방에 새로 들어오거나 오래 자리를 비웠다 돌아오면 한 번에 수십~수백 건을
  읽음 처리하게 되는데, 이 경우 왕복 횟수가 그대로 비례해서 늘어난다.

여기에 `MessageReadHandler.java:52-63`에서 이 호출 전에 이미 `messageRepository.findById`(roomId
확인용) + `userRepository.findById` + `roomRepository.findById`를 추가로 하기 때문에, 읽음 처리
이벤트 하나가 메시지 ID 개수에 비례한 쿼리 폭탄이 된다.

## 조치 ✅

MongoDB `updateMany`/bulk write로 한 번에 처리 — "이미 읽음" 체크는 애플리케이션 코드로 배열을
순회하는 대신 쿼리 필터(`readers.userId: {$ne: userId}`)로 대체.

`MessageReadStatusService.updateReadStatus`를 `MongoTemplate.updateMulti` 단일 bulk write로
교체했다:

```java
Query query = new Query(Criteria.where("id").in(messageIds)
        .and("readers.userId").ne(userId));
Update update = new Update().push("readers", readerInfo);
mongoTemplate.updateMulti(query, update, Message.class);
```

"이미 읽었는지" 체크는 애플리케이션에서 배열을 순회하는 대신 쿼리 필터로 옮겨서 MongoDB가 조건에
맞는 문서에만 `$push`를 적용하게 했다 — 메시지 ID 몇 개를 처리하든 항상 **1회 왕복**이다.

`MessageReadHandler.handleMarkAsRead`가 `updateReadStatus` 호출 전에 하는 `findById` 3회(roomId
확인 + user + room)는 이번 수정 범위 밖이라 그대로 남아있다 — 읽음 처리 이벤트 하나당 고정 3회
왕복은 여전하다.

## 측정 방법

스크립트: `apps/backend/perf-artillery/mark-as-read-load.js` (git 미추적).

**시나리오**: 유저 30명을 방 1개에 소켓으로 동시 조인시키고, 유저 0이 메시지 N건을 먼저 시딩한다
(스크롤 후 한꺼번에 밀린 메시지를 읽음 처리하는 상황을 모사). 그 뒤 30명 전원이 **동시에** 자신의
`markMessagesAsRead` 이벤트를 발행해서 그 N건 전부를 한 번의 호출로 읽음 처리한다.

각 유저의 emit 시각부터 방 브로드캐스트로 돌아오는 자신의 `messagesRead` 응답을 받기까지의
왕복시간을 측정한다. N(메시지 개수)을 50건/200건 두 가지로 바꿔가며 "왕복 횟수가 N에 비례하던 게
상수로 바뀌었는가"를 함께 확인한다.

기능 정확성은 별도 스크립트 `verify-read-status-fix.js`로 확인했다 — 읽음 처리 후 `readers`
배열에 정확히 반영되는지, 같은 유저가 같은 메시지를 두 번 읽음 처리해도 중복 추가되지 않는지
(bulk update의 쿼리 필터가 의도대로 동작하는지) 검증 통과.

### 재현 명령

```bash
cd apps/backend && make dev   # 코드를 원하는 상태로 맞추고 재기동

cd apps/backend/perf-artillery
NODE_PATH=<repo-root>/loadtest/node_modules node mark-as-read-load.js --users=30 --messages=50
NODE_PATH=<repo-root>/loadtest/node_modules node mark-as-read-load.js --users=30 --messages=200
```

## 측정 결과

| 지표 | 수정 전 (50건) | 수정 후 (50건) | 수정 전 (200건) | 수정 후 (200건) |
|---|---|---|---|---|
| p50 | 99ms | **30ms (-70%)** | 240ms | **43ms (-82%)** |
| p95 | 116ms | **33ms (-72%)** | 323ms | **48ms (-85%)** |
| max | 116ms | **33ms** | 328ms | **48ms** |
| 전체 처리 시간(30명 동시) | 202ms | **101ms** | 405ms | **102ms** |

**해석**: 가장 뚜렷한 신호는 **메시지 개수를 4배(50→200) 늘렸을 때의 반응**이다.

- 수정 전: p50이 99ms → 240ms로 **거의 선형(2.4배)**하게 늘어난다 — "메시지 ID당 find+save
  반복" 코드니 당연한 결과다.
- 수정 후: p50이 30ms → 43ms로 **거의 그대로**다(43% 증가에 그침, 그것도 응답 페이로드 크기
  증가 등 부수 비용일 가능성이 크다) — bulk update 1회로 바뀌었으니 메시지 개수와 무관하게
  거의 상수 시간이 되는 게 이론과 맞아떨어진다.

이 패턴(N에 비례 → 상수)은 [4번 항목](04-chatmessage-duplicate-session.md)(고정 2회 감소)보다
구조적으로 더 큰 개선이다 — 특히 유저가 오랜만에 방에 들어와 한 번에 수백 건을 읽음 처리하는
"콜드 스타트" 시나리오에서 효과가 극대화된다.

## 남은 일

- `MessageReadHandler.handleMarkAsRead`가 `updateReadStatus` 호출 전에 하는 `findById` 3회
  (roomId 확인 + user + room, `MessageReadHandler.java:52-67`)는 이번 수정 범위 밖이라 그대로
  남아있다.
- [6번 항목](06-message-loader-n-plus-1.md)(히스토리 스크롤 발신자 조회 N+1)은 이번에 손대지
  않았다 — `updateReadStatus`와는 다른 코드 경로(`MessageLoader.java:66-76`)다.
