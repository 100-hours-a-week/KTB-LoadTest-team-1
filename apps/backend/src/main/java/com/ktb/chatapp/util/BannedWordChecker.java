package com.ktb.chatapp.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.ahocorasick.trie.Trie;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Trie trie;

    public BannedWordChecker(Set<String> bannedWords) {
        List<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .toList();
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        // Aho-Corasick 자동자: 사전 크기와 무관하게 메시지 길이에 비례하는 O(n)으로
        // 모든 금칙어를 한 번의 스캔으로 탐색한다(사전 크기만큼 순차 contains()를 반복하던
        // 이전 방식 대체). onlyWholeWords()를 안 걸었으므로 단어가 메시지 중간에
        // 부분 문자열로 껴 있어도 그대로 잡힌다 — 기존 String.contains() 동작과 동일하다.
        this.trie = Trie.builder().addKeywords(normalizedWords).build();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        return trie.containsMatch(normalizedMessage);
    }
}
