# 10. Socket.IO 서버 설정

> **한줄 요약**: 연결 대기열이 너무 작고(10), Nagle이 켜져 있고, 멀티 인스턴스 확장이 막혀 있었다.
> 멀티 인스턴스 문제는 해결([16번 항목](16-multi-instance-socketio.md)). 대기열/Nagle 튜닝은 아직.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 🟡 부분 완료 | 소켓 연결 폭주 시나리오, 지연시간 | Medium |

## 문제

`src/main/java/com/ktb/chatapp/config/SocketIOConfig.java`

| 설정 | 현재 값 | 문제 | 상태 |
|---|---|---|---|
| `setAcceptBackLog` | 10 | TCP accept 대기열이 10. `ramp-up-test.js`처럼 짧은 시간에 다수의 소켓 연결이 몰리면 연결 자체가 거부될 수 있다. | 미착수 |
| `setTcpNoDelay` | `false` | Nagle 알고리즘이 켜져 있음. 채팅처럼 작은 메시지를 자주 보내는 프로토콜엔 보통 `true`(비활성화) 권장 — 지연시간(`load-test.js`의 message latency 지표)에 영향을 줄 수 있다. | 미착수 |
| `MemoryStoreFactory()` | 단일노드 전용 | 백엔드를 여러 인스턴스로 수평 확장하면 룸 멤버십/커넥션 상태가 인스턴스별로 분리돼 브로드캐스트가 깨진다. | ✅ 완료 — [16번 항목](16-multi-instance-socketio.md) 참고 |

## 조치

- ✅ **멀티 인스턴스 스토어**: `socketio.store.type=redis`로 `RedissonStoreFactory` +
  `RedisChatDataStore`를 옵션으로 추가. 자세한 내용/검증 결과는 [16번 항목](16-multi-instance-socketio.md).
- ⬜ `acceptBackLog`를 REST용 Tomcat `accept-count`([1번 항목](01-tomcat-thread-pool.md))와 비슷한
  수준으로 상향 — 아직 미착수.
- ⬜ `tcpNoDelay(true)`로 전환 후 지연시간 재측정 — 아직 미착수.
