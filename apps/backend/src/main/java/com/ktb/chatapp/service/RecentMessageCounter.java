package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomMessageCount;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    /**
     * 방 여러 개의 최근 메시지 수를 한 번의 쿼리로 조회한다.
     * 결과에 없는 방(최근 메시지가 0건인 방)은 호출부에서 0으로 취급하면 된다.
     */
    public Map<String, Integer> countRecentMessages(Collection<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return messageRepository.countRecentMessagesByRoomIds(roomIds, since).stream()
                .collect(Collectors.toMap(RoomMessageCount::getId, rc -> (int) rc.getCount()));
    }
}
