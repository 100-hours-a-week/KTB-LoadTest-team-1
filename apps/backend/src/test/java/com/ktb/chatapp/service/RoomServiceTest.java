package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * REST 방 입장(joinRoom)이 원자적 $addToSet(addParticipant)을 쓰는지, 그리고 그 앞뒤 검증
 * 흐름(비밀번호, 사용자 조회)이 그대로 유지되는지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoomService 단위 테스트")
class RoomServiceTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_EMAIL = "user@example.com";
    private static final String USER_ID = "user-1";
    private static final String RAW_PASSWORD = "1234";
    private static final String ENCODED_PASSWORD = "encoded-1234";

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RecentMessageCounter recentMessageCounter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
                roomRepository, userRepository, recentMessageCounter, passwordEncoder, eventPublisher);
    }

    @Test
    @DisplayName("입장 성공 시 addParticipant로 원자적 갱신하고 최신 방을 재조회한다")
    void joinRoom_success_usesAtomicAddParticipant() {
        Room roomBeforeJoin = roomWithParticipants(Set.of());
        Room roomAfterJoin = roomWithParticipants(Set.of(USER_ID));

        when(roomRepository.findById(ROOM_ID))
                .thenReturn(Optional.of(roomBeforeJoin))
                .thenReturn(Optional.of(roomAfterJoin));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user()));
        when(userRepository.findAllById(any())).thenReturn(List.of(user()));
        when(recentMessageCounter.countRecentMessages(ROOM_ID)).thenReturn(0);

        Room result = roomService.joinRoom(ROOM_ID, null, USER_EMAIL);

        verify(roomRepository, times(1)).addParticipant(ROOM_ID, USER_ID);
        verify(roomRepository, never()).save(any());
        assertThat(result.getParticipantIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("이미 참여중인 사용자가 다시 입장해도 addParticipant를 그대로 호출한다(no-op)")
    void joinRoom_alreadyParticipant_stillCallsAddParticipant() {
        Room room = roomWithParticipants(Set.of(USER_ID));

        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user()));
        when(userRepository.findAllById(any())).thenReturn(List.of(user()));
        when(recentMessageCounter.countRecentMessages(ROOM_ID)).thenReturn(0);

        Room result = roomService.joinRoom(ROOM_ID, null, USER_EMAIL);

        verify(roomRepository, times(1)).addParticipant(ROOM_ID, USER_ID);
        assertThat(result.getParticipantIds()).containsExactly(USER_ID);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 addParticipant를 호출하지 않고 예외를 던진다")
    void joinRoom_wrongPassword_rejectsBeforeAddingParticipant() {
        Room room = roomWithPassword(ENCODED_PASSWORD);

        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(false);

        assertThatThrownBy(() -> roomService.joinRoom(ROOM_ID, RAW_PASSWORD, USER_EMAIL))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("비밀번호가 일치하지 않습니다.");

        verify(roomRepository, never()).addParticipant(anyString(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 방이면 null을 반환한다")
    void joinRoom_missingRoom_returnsNull() {
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        Room result = roomService.joinRoom(ROOM_ID, null, USER_EMAIL);

        assertThat(result).isNull();
        verify(roomRepository, never()).addParticipant(anyString(), anyString());
    }

    private Room roomWithParticipants(Set<String> participantIds) {
        return Room.builder()
                .id(ROOM_ID)
                .name("테스트 방")
                .creator(USER_ID)
                .hasPassword(false)
                .participantIds(new HashSet<>(participantIds))
                .build();
    }

    private Room roomWithPassword(String encodedPassword) {
        return Room.builder()
                .id(ROOM_ID)
                .name("테스트 방")
                .creator(USER_ID)
                .hasPassword(true)
                .password(encodedPassword)
                .participantIds(new HashSet<>())
                .build();
    }

    private User user() {
        return User.builder()
                .id(USER_ID)
                .email(USER_EMAIL)
                .name("테스트유저")
                .build();
    }
}
