# 5. 금칙어 필터: 메시지 1건마다 10,000개 단어 naive 스캔

> **한줄 요약**: 메시지가 올 때마다 금칙어 10,000개를 전부 `contains()`로 뒤지고 있었다.
> Aho-Corasick 자동자로 교체 — 메시지 1건당 약 **1,000배** 빨라짐(216us → 0.17us).

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 수정 완료 | 채팅 메시지 처리량(CPU) | Critical |

## 문제

`src/main/java/com/ktb/chatapp/util/BannedWordChecker.java`(수정 전)

```java
public boolean containsBannedWord(String message) {
    ...
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return bannedWords.stream().anyMatch(normalizedMessage::contains);
}
```

- `fake_banned_words_10k.txt`는 실제로 10,000줄이다(`wc -l` 확인).
- 메시지 하나가 들어올 때마다 최악의 경우 10,000번의 `String.contains()` 부분 문자열 검색이
  순차로 돈다.
- 전형적인 O(사전 크기 × 메시지 길이) CPU 바운드 핫패스다.
- `ChatMessageHandler.handleChatMessage`에서 메시지마다 무조건 호출된다.

메시지 처리량이 늘어날수록 CPU가 이 필터에 잡아먹힌다.

## 조치 ✅

Aho-Corasick 멀티패턴 매칭 자동자(`org.ahocorasick:ahocorasick`)로 교체했다. 사전 크기와
무관하게 메시지 길이에 비례하는 O(n) 매칭이 된다 — 자동자를 만드는 비용은 애플리케이션 시작
시 사전 로드할 때 한 번만 든다.

```java
public BannedWordChecker(Set<String> bannedWords) {
    List<String> normalizedWords = bannedWords.stream()
            .filter(word -> word != null && !word.isBlank())
            .map(word -> word.toLowerCase(Locale.ROOT))
            .toList();
    Assert.notEmpty(normalizedWords, "Banned words set must not be empty");
    this.trie = Trie.builder().addKeywords(normalizedWords).build();
}

public boolean containsBannedWord(String message) {
    if (message == null || message.isBlank()) {
        return false;
    }
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return trie.containsMatch(normalizedMessage);
}
```

`onlyWholeWords()`를 걸지 않아서, 기존 `String.contains()`와 동일하게 단어가 메시지 중간에
부분 문자열로 껴 있어도 그대로 잡힌다 — 동작(semantics)은 그대로 유지하고 알고리즘만 바꿨다.
기존 `BannedWordCheckerTest`(정확히 일치/문장에 섞여 있음/깨끗한 문장·null·공백) 3개 테스트를
그대로 통과한다.

## 측정 방법

`BannedWordChecker.containsBannedWord`를 직접 마이크로벤치마크했다. HTTP/소켓/DB를 거치지 않고
메서드 호출만 반복 측정한다 — 이 항목은 순수 CPU 바운드 알고리즘 문제라, `chatMessage` 전체
처리 시간(DB 왕복이 대부분을 차지)으로 재면 이 부분의 개선이 노이즈에 묻히기 때문이다.

**시나리오**: 실제 `fake_banned_words_10k.txt`(10,000단어)를 로드해서 `BannedWordChecker`를
만들고, 두 종류의 메시지를 각각 20만 번 호출한다.
- **clean**: 금칙어가 없는 문장 (최악의 경우 — naive 구현은 10,000개를 전부 다 검사해야 함)
- **dirty**: 사전 중간(5,000번째) 단어가 섞인 문장 (naive 구현은 평균적으로 절반만 검사하고
  멈춤 — `anyMatch`가 short-circuit하므로)

기능 정확성은 `verify-banned-word-fix.js`로 실제 `chatMessage` 이벤트를 통해 별도 확인했다 —
금칙어 포함 메시지는 `MESSAGE_REJECTED`로 거부되고, 깨끗한 메시지는 정상 전송되는 것을 검증
통과.

## 측정 결과

| 메시지 유형 | 수정 전 (naive, 20만 회 평균) | 수정 후 (Aho-Corasick, 20만 회 평균) | 배율 |
|---|---|---|---|
| clean (최악의 경우) | 216.343 us/call | **0.167 us/call** | **약 1,295배** |
| dirty (중간에 매칭) | 122.410 us/call | **0.132 us/call** | **약 927배** |

**해석**: 알고리즘 복잡도 자체가 O(사전 크기 × 메시지 길이)에서 O(메시지 길이)로 바뀐 결과가
그대로 드러난다. 사전 크기(10,000)가 그대로 배율에 반영되는 걸 확인할 수 있다 — 사전이 더
커지면(운영 중 금칙어를 추가하면) naive 구현은 그만큼 느려지지만, Aho-Corasick은 자동자를
다시 빌드하는 비용만 늘 뿐 매칭 자체는 거의 영향을 안 받는다.

메시지당 몇백 us 단위라 [4](04-chatmessage-duplicate-session.md)·
[7번 항목](07-mark-as-read-bulk-update.md)(DB 왕복, 몇~수십 ms 단위)보다 절대 크기는 작지만,
동시 메시지 처리량이 높아질수록(로드테스트 스파이크 구간) CPU 하나가 이 필터에 묶여 있던
시간이 통째로 사라진다는 의미가 있다.
