package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    /**
     * 방 여러 개의 최근 메시지 수를 한 번의 쿼리로 조회한다.
     * 결과에 없는 방(최근 메시지가 0건인 방)은 호출부에서 0으로 취급하면 된다.
     *
     * MongoTemplate으로 raw Document를 직접 읽는다 — 리포지토리 {@code @Aggregation} +
     * 인터페이스 프로젝션 조합은 _id 그룹 키를 프로젝션 프로퍼티로 바인딩하지 못해(검증됨,
     * getId()가 항상 null을 반환해 Collectors.toMap에서 "Duplicate key null"로 터짐)
     * 이 방식으로 우회한다.
     */
    public Map<String, Integer> countRecentMessages(Collection<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);

        Aggregation aggregation = newAggregation(
                match(Criteria.where("room").in(roomIds).and("timestamp").gte(since)),
                group("room").count().as("count")
        );

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, "messages", Document.class);

        Map<String, Integer> counts = new HashMap<>();
        for (Document doc : results.getMappedResults()) {
            counts.put(doc.getString("_id"), doc.getInteger("count"));
        }
        return counts;
    }
}
