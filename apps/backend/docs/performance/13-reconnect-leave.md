# 13. 소켓 재접속(새로고침/네트워크 순단)이 "방 나가기"로 처리됨

> **한줄 요약**: 연결이 끊기면 서버가 "방을 나갔다"고 착각해서 참가자를 지우고 퇴장 메시지를
> 저장한다. 백엔드 쪽 원인은 고쳐서 검증까지 했지만, 프론트가 새로고침 시 스스로 나가기를
> 보내는 별개의 원인이 있어서 일단 롤백했다 — 프론트와 같이 고쳐야 완전히 해소된다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ⏸️ 보류 — 프론트 수정 필요 (백엔드만으론 부분적 해결) | 재접속이 몰리는 모든 시나리오(스파이크와 정확히 겹침) | 기능 버그 + 성능 |

## 문제

`ConnectionLoginHandler.onDisconnect`(수정 전)는 유저가 있던 방 전체(R개)에 대해
`RoomLeaveHandler.handleLeaveRoom`을 통째로 실행했다:

- `roomRepository.removeParticipant`로 **MongoDB에서 실제로 참가자를 제거**
- "OO님이 퇴장하였습니다" 메시지를 저장

`onConnect`는 자동으로 그 방들에 재입장(`RoomJoinHandler.handleJoinRoom` 전체 실행 — 참가자
재추가, "입장하였습니다" 메시지 저장, 메시지 30개 재로드, 그 안에서
[6](06-message-loader-n-plus-1.md)·[7번 항목](07-mark-as-read-bulk-update.md)의 N+1까지 재발동)
시키려 했다.

**실측해보니 이 자동 재입장은 죽은 코드였다** — `onDisconnect`가 이미 `userRooms`(서버 메모리상의
"이 유저가 있던 방" 추적)를 비워버려서, `onConnect` 시점엔 재입장시킬 목록이 항상 비어있었다.
대신 프론트(`useChatRoomLifecycle.js`)가 재연결 시 지금 보고 있는 방 하나만 명시적으로 재입장
(`rejoinRoom()`)시키고 있었다.

**결과**: 와이파이 순단이든 F5 새로고침이든, 연결이 끊기는 순간 서버는 이걸 "진짜로 방을 나간
것"과 구분하지 못했다.

- 참가자 목록이 실제로 사라졌다가 다시 나타난다(다른 유저 화면에 깜빡임).
- "입장/퇴장하였습니다" 시스템 메시지가 재접속마다 채팅 기록에 영구히 쌓인다(스팸).
- 유저가 있던 방 개수(R)에 비례해 DB 왕복(제거/추가/메시지 저장/참가자 N+1)이 매 연결 끊김마다
  발생한다.

## 시도한 조치 (현재 롤백됨)

`ConnectionLoginHandler.onDisconnect`가 더 이상 `RoomLeaveHandler.handleLeaveRoom`을 호출하지
않고 `userRooms.clear(userId)`(서버 메모리 추적 정리)만 하도록 고쳐서 아래 측정치까지 확인했다.

하지만 **실제로 브라우저에서 새로고침을 재현해보니 여전히 입장/퇴장 메시지가 떴다.** 원인을
보니 프론트엔드(`apps/frontend/features/chat/room/useChatRoomLifecycle.js:247-258`)가
`beforeunload` 시점에 `socketClient.tryLeaveRoom(roomId, socket)`을 **명시적으로 호출**하고
있었다. 이건 `onDisconnect`가 아니라 `@OnEvent(LEAVE_ROOM)`(진짜 나가기) 경로를 그대로 타므로,
백엔드 수정과 무관하게 여전히 DB 제거 + 퇴장 메시지가 발생한다.

즉 원인이 두 군데였다:

1. **연결이 뚝 끊길 때**(서버가 감지, `onDisconnect`) — 백엔드만으로 해결 가능(위에서 시도한 수정).
2. **새로고침/탭 닫기처럼 정상 종료가 예고될 때**(`beforeunload`) — **프론트엔드가 스스로 나가기를
   보내는 것**이라 백엔드 수정과 무관하게 재현된다.

새로고침 시나리오까지 실제로 없애려면 프론트의 `beforeunload` 핸들러도 같이 고쳐야 하는데, 이번
세션은 `apps/backend`만 수정하기로 해서 범위 밖이다. 백엔드 쪽 수정만 단독으로 넣으면 "보이는
증상은 그대로인데 코드는 부분적으로 다른 상태"가 되어 혼란스러울 수 있어 일단 롤백했다(현재
코드는 `RoomLeaveHandler.handleLeaveRoom`을 disconnect마다 그대로 호출하는 원래 상태). 프론트
수정과 같이 진행할 때 아래 실측치를 그대로 재사용하면 된다.

## 측정 방법

REST가 아니라 소켓 연결 끊김/재연결 시나리오라 Artillery 대신 `socket.io-client`로 직접 시뮬레이션
(`apps/backend/perf-artillery/reconnect-leave-cost.js`).

유저 20명이 방 5개에 소켓으로 조인(=`userRooms`에 유저당 방 5개 추적됨) → 20명 전원 동시
`disconnect()` → `messages` 컬렉션 문서 수가 안정화될 때까지 폴링해서 순증가량과 안정화까지
걸린 시간을 측정.

## 측정 결과

| 지표 | 코드 수정 전 | `ConnectionLoginHandler` 수정 시도 후 |
|---|---|---|
| 퇴장 스팸 메시지("OO님이 퇴장하였습니다") 증가량 | **100건** (유저20 × 방5) | **0건** |
| disconnect 발행 ~ DB 쓰기 안정화까지 | 2,242ms | ~즉시 (쓸 게 없음) |

**이 측정은 `onDisconnect`(연결이 뚝 끊기는 경우)만 재현한다.** `beforeunload`(새로고침) 경로는
프론트 수정 없이는 이 수치에 반영되지 않는다 — 위 "시도한 조치" 절 참고.

## 남은 결정 사항

새로고침 케이스까지 없애려면 프론트(`useChatRoomLifecycle.js`)도 같이 고쳐야 한다. 이번 세션은
backend-only 스코프라 아직 미결정:

- (a) 발견 사항으로만 기록해두고 다음 세션에서 프론트와 같이 처리한다.
- (b) 이번에 한해 예외로 프론트도 같이 고친다.
