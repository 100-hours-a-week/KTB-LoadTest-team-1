# 15. 인증된 REST 요청마다 세션을 무조건 find+save

> **한줄 요약**: 로그인이 필요한 REST API 전부가(방 목록, 방 생성, 파일 업로드 등) 인증
> 필터 단계에서 세션 문서를 매번 find+save 했다. "최근에 이미 갱신했으면 쓰기 생략"하는
> 디바운스를 넣었다 — 짧은 시간에 반복 요청하는 시나리오에서 MongoDB update 연산이
> **2,500건 → 0건**으로 줄었다.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 인증이 필요한 REST API 전체(`/api/**`, 로그인/헬스체크/스웨거 제외) | Critical |

## 문제

`SecurityConfig.java`에서 `SessionAwareJwtAuthenticationConverter`가 OAuth2 Resource Server의
JWT 인증 컨버터로 등록돼 있다:

```java
.oauth2ResourceServer(oauth2 -> oauth2
        .bearerTokenResolver(bearerTokenResolver)
        .jwt(jwt -> jwt
                .decoder(jwtDecoder)
                .jwtAuthenticationConverter(jwtAuthenticationConverter)
        )
);
```

즉 `.requestMatchers("/api/**").authenticated()`에 해당하는 **모든 인증된 REST 요청**이 컨트롤러
로직을 타기 전에 이 컨버터를 거친다:

```java
public AbstractAuthenticationToken convert(Jwt jwt) {
    ...
    SessionValidationResult validation = sessionService.validateSession(userId, sessionId);
    ...
}
```

그리고 `SessionService.validateSession`(수정 전)은 세션이 유효하기만 하면 **매번 무조건**
find + save를 한다:

```java
Session session = sessionStore.findByUserId(userId).orElse(null);
...
// Update last activity
session.setLastActivity(now);
session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
session = sessionStore.save(session);   // 매 요청마다, 무조건
```

[4번 항목](04-chatmessage-duplicate-session.md)은 `chatMessage` 소켓 이벤트 안에서 이 메서드가
중복 호출되던 걸 없앤 것이었다 — `validateSession` 자체가 "호출될 때마다 무조건 쓴다"는 이
근본 패턴은 건드리지 않았다. 이번 항목은 스코프가 훨씬 넓다: `GET /api/rooms`, 방 생성/입장,
파일 업로드·다운로드 등 **인증이 필요한 REST 엔드포인트 전부**가 원래 하려던 일 외에 세션
문서 쓰기를 매번 추가로 지불하고 있었다.

`lastActivity`를 갱신하는 목적은 "세션이 30분(`SESSION_TTL`) 동안 안 쓰면 만료되게 하는 것"
뿐인데, 같은 유저가 1초에 여러 번 요청을 보내도 매번 새로 썼다 — 갱신 자체는 몇 분에 한 번만
해도 충분한데 요청마다 하고 있었다.

## 조치 ✅

"최근에 이미 갱신했으면 쓰기 생략"하는 디바운스를 넣었다. 세션 타임아웃이 30분이니 1분 정도의
지연은 안전마진이 충분하다:

```java
private static final long LAST_ACTIVITY_REFRESH_THRESHOLD_MS = Duration.ofMinutes(1).toMillis();

// 만료 여부 검사는 그대로 매번 수행(보안에 영향 없음)
...

// 최근에 이미 갱신했으면(임계값 이내) 쓰기를 건너뛴다 — 읽은 세션을 그대로 유효 처리.
if (now - session.getLastActivity() < LAST_ACTIVITY_REFRESH_THRESHOLD_MS) {
    return SessionValidationResult.valid(toSessionData(session));
}

// Update last activity
session.setLastActivity(now);
session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
session = sessionStore.save(session);
```

읽기(find)는 여전히 매번 한다 — 세션이 유효한지/만료됐는지는 매 요청 반드시 확인해야 하므로
보안·정합성에는 영향이 없다. 줄어드는 건 쓰기(save)뿐이다.

기존 유닛테스트 중 `validateSession_UpdatesLastActivity`가 "100ms 뒤에 재검증하면 lastActivity가
반드시 갱신돼야 한다"고 검증하고 있었는데, 이건 정확히 이번에 없애려는 동작이라 디바운스
동작을 검증하도록 다시 썼다(`validateSession_WithinRefreshThreshold_DoesNotRewriteLastActivity`).
`SessionServiceTest` 21개 전부 통과 확인.

## 측정 방법

스크립트: `apps/backend/perf-artillery/session-validate-load.js` (git 미추적).

**시나리오**: 유저 N명이 각자 로그인한 뒤, 같은 세션으로 `GET /api/users/profile`(가벼운 인증
엔드포인트 — 유저 1명만 조회, 목록성 데이터 없음)을 M번 연속으로 쏜다. 같은 세션이 짧은
간격으로 반복 요청하는, 디바운스가 가장 잘 먹는 패턴이다.

`GET /api/rooms`로 재려다가 그만뒀다 — 이 세션 내내 부하테스트를 반복하면서 방이 260개 넘게
쌓여([3번 항목](03-get-rooms-n-plus-1.md)의 남은 문제, `findAll()`이 매번 전체를 로드), 그
자체의 응답 시간이 세션 검증 비용보다 훨씬 커서 신호가 묻혔다.

### 두 가지 지표를 같이 봤다

- **HTTP 요청 지연시간(p50/p95/p99)**: 실제로 재보니 수정 전후 차이가 노이즈 수준이었다 —
  로컬 Mongo 왕복 1~2ms 절감분이 Node.js 클라이언트 오버헤드/TCP 연결/Spring MVC 디스패치
  같은 다른 비용에 묻혔다.
- **MongoDB `update` 연산 횟수(`db.serverStatus().opcounters.update`)**: 부하 스크립트
  실행 직전/직후 값을 찍어서 델타를 구했다. 이쪽이 훨씬 깨끗한 신호였다 — 이 시나리오에서
  세션 문서에 쓰기를 하는 코드는 `validateSession`뿐이라, 델타가 사실상 "이번 수정이 없앤
  쓰기 횟수"와 정확히 일치한다.

### 재현 명령

```bash
cd apps/backend && make dev

cd apps/backend/perf-artillery
BEFORE=$(mongosh "mongodb://localhost:27017/bootcamp-chat" --quiet --eval "print(db.serverStatus().opcounters.update)" | grep -o '[0-9]*')
NODE_PATH=<repo-root>/loadtest/node_modules node session-validate-load.js --users=50 --requests-per-user=50
AFTER=$(mongosh "mongodb://localhost:27017/bootcamp-chat" --quiet --eval "print(db.serverStatus().opcounters.update)" | grep -o '[0-9]*')
echo "델타: $((AFTER - BEFORE))"
```

## 측정 결과

**HTTP 요청 지연시간(유저 50명 × 요청 50회 = 총 2,500건, 동시 실행)**

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| p50 | 6~7ms | 6ms |
| p95 | 14~19ms | 10~16ms |
| p99 | 36~45ms | 16~41ms |
| max | 47~52ms | 17~46ms |

수정 전후로 뚜렷한 차이라고 말하기 어렵다 — 값이 겹친다. 로컬 Mongo 기준 왕복이 워낙 빨라서,
줄어든 쓰기 한 번(1~2ms)이 요청 전체 경로(Node 클라이언트, TCP, Spring 디스패치 등)의 노이즈에
묻힌 것으로 판단된다.

**MongoDB update 연산 횟수(같은 2,500건 요청 기준)**

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| update 연산 델타 | **2,500건** | **0건** |

**해석**: 이게 훨씬 명확한 증거다. 수정 전에는 요청 1건당 정확히 update 1번(2,500건 요청 →
2,500번 쓰기) — 코드 분석과 정확히 일치한다. 수정 후에는 이 테스트가 60초짜리 디바운스 창
안에서 전부 끝나기 때문에(전체 소요 300~400ms) 쓰기가 **단 한 번도** 발생하지 않았다.

HTTP 레이턴시만 봤다면 "효과가 없다"고 잘못 결론 내렸을 수 있다 — 실제로는 DB 쓰기 부하가
100% 줄었는데, 그 절감분이 로컬 환경의 다른 오버헤드에 가려 최종 응답 시간에는 안 드러난
것뿐이다. 배포 환경처럼 DB와의 네트워크 거리가 있거나, MongoDB 자체가 다른 부하로 바쁠 때는
이 차이가 응답 시간에도 드러날 가능성이 높다 — 무엇보다 **DB에 걸리는 총 쓰기 부하 자체가
줄어드는 것** 자체가 이 수정의 핵심 가치다(응답 시간 단축은 부수 효과).

## 남은 일

`updateLastActivity`(별도 메서드)는 이번 수정 범위 밖이다 — 현재 호출하는 곳이 없어 죽은
코드지만, 혹시 나중에 다시 쓰이게 되면 같은 디바운스를 적용해야 한다.
