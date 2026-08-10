package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByRoomIdAndTimestampBefore(String roomId, LocalDateTime timestamp, Pageable pageable);
    /**
     * 특정 시간 이후의 메시지 수 카운트
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    /**
     * 여러 방의 최근 메시지 수를 한 번의 집계 쿼리로 조회한다.
     * 방 목록 조회(GET /api/rooms)에서 방마다 개별 COUNT 쿼리를 날리던 N+1을 없애기 위함.
     */
    @Aggregation(pipeline = {
        "{ $match: { 'room': { $in: ?0 }, 'timestamp': { $gte: ?1 } } }",
        "{ $group: { _id: '$room', count: { $sum: 1 } } }"
    })
    List<RoomMessageCount> countRecentMessagesByRoomIds(Collection<String> roomIds, LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);
}
