# 10. Socket.IO 서버 설정

> **한줄 요약**: 연결 대기열이 너무 작고(10), Nagle이 켜져 있고, 멀티 인스턴스 확장이 막혀 있다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | 소켓 연결 폭주 시나리오, 지연시간 | Medium |

## 문제

`src/main/java/com/ktb/chatapp/config/SocketIOConfig.java`

| 설정 | 현재 값 | 문제 |
|---|---|---|
| `setAcceptBackLog` (49번 줄) | 10 | TCP accept 대기열이 10. `ramp-up-test.js`처럼 짧은 시간에 다수의 소켓 연결이 몰리면 연결 자체가 거부될 수 있다. |
| `setTcpNoDelay` (48번 줄) | `false` | Nagle 알고리즘이 켜져 있음. 채팅처럼 작은 메시지를 자주 보내는 프로토콜엔 보통 `true`(비활성화) 권장 — 지연시간(`load-test.js`의 message latency 지표)에 영향을 줄 수 있다. |
| `MemoryStoreFactory()` (72번 줄) | 단일노드 전용 | 주석에도 명시돼 있음. 백엔드를 여러 인스턴스로 수평 확장하면 룸 멤버십/커넥션 상태가 인스턴스별로 분리돼 브로드캐스트가 깨진다. |

## 조치 (미착수)

- `acceptBackLog`를 REST용 Tomcat `accept-count`([1번 항목](01-tomcat-thread-pool.md))와 비슷한
  수준으로 상향.
- `tcpNoDelay(true)`로 전환 후 지연시간 재측정.
- 수평 확장 계획이 있다면 `MemoryStoreFactory` → Redis 기반 store로 교체 검토
  ([8번 항목](08-redis-unused.md)과 연계).
