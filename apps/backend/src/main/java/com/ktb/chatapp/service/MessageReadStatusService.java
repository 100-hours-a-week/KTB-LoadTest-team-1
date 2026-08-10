package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds.isEmpty()) {
            return;
        }

        var readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        try {
            // 메시지 ID당 find+save를 반복하는 대신, "아직 이 유저가 안 읽은 메시지"만
            // 쿼리 필터로 걸러서 한 번의 bulk update로 처리한다.
            Query query = new Query(Criteria.where("id").in(messageIds)
                    .and("readers.userId").ne(userId));
            Update update = new Update().push("readers", readerInfo);
            var result = mongoTemplate.updateMulti(query, update, Message.class);

            log.debug("Read status updated for {} of {} messages by user {}",
                    result.getModifiedCount(), messageIds.size(), userId);
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
