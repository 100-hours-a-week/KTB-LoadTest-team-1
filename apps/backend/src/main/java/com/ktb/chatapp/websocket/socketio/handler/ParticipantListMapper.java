package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 방 참가자 목록을 UserResponse로 변환한다. 참가자 수만큼 개별 findById를 반복하는 대신
 * findAllById로 한 번에 배치 조회한다(RoomJoinHandler/RoomLeaveHandler가 입장·퇴장마다
 * 중복으로 갖고 있던 N+1 패턴을 한 곳으로 통합).
 */
@Component
@RequiredArgsConstructor
public class ParticipantListMapper {

    private final UserRepository userRepository;

    public List<UserResponse> toParticipantList(Room room) {
        Map<String, User> userCache = new HashMap<>();
        for (User user : userRepository.findAllById(room.getParticipantIds())) {
            userCache.put(user.getId(), user);
        }

        return room.getParticipantIds().stream()
                .map(userCache::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();
    }
}
