# 5. 금칙어 필터: 메시지 1건마다 10,000개 단어 naive 스캔

> **한줄 요약**: 메시지가 올 때마다 금칙어 10,000개를 전부 `contains()`로 뒤진다. DB가 아니라 CPU가 병목.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| 미착수 | 채팅 메시지 처리량(CPU) | Critical |

## 문제

`src/main/java/com/ktb/chatapp/util/BannedWordChecker.java:24-29`

```java
public boolean containsBannedWord(String message) {
    ...
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return bannedWords.stream().anyMatch(normalizedMessage::contains);
}
```

- `fake_banned_words_10k.txt`는 실제로 10,000줄이다(`wc -l` 확인).
- 메시지 하나가 들어올 때마다 최악의 경우 10,000번의 `String.contains()` 부분 문자열 검색이 순차로 돈다.
- 전형적인 O(사전 크기 × 메시지 길이) CPU 바운드 핫패스다.
- `ChatMessageHandler.handleChatMessage`(141번 줄)에서 메시지마다 무조건 호출된다.

메시지 처리량이 늘어날수록 CPU가 이 필터에 잡아먹힌다. 이미 스레드 풀이 작은 상황
([1](01-tomcat-thread-pool.md)·[4번 항목](04-chatmessage-duplicate-session.md))에서 CPU까지
여기서 소모되면 전체 처리량이 더 떨어진다.

## 조치 (미착수)

- Aho-Corasick 같은 멀티패턴 매칭 알고리즘으로 교체(예: `org.ahocorasick:ahocorasick`).
- 사전 크기와 무관하게 메시지 길이에 비례하는 O(n) 매칭이 가능해진다.
- 착수 전에 Grafana `process_cpu_usage`/`system_cpu_usage` 패널로 이 필터가 실제로 CPU를
  얼마나 잡아먹는지 먼저 프로파일링해서 우선순위를 확인하는 걸 권장한다.
