package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionStore sessionStore;
    public static final long SESSION_TTL_SEC = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();
    private static final long SESSION_TIMEOUT = SESSION_TTL_SEC * 1000;

    /**
     * lastActivity를 실제로 다시 쓰기까지 허용하는 최소 간격. 세션 TTL(30분)에 비하면 짧은
     * 유예라, 같은 유저가 짧은 시간에 여러 요청을 보내도(REST 요청마다 validateSession이
     * 인증 필터에서 호출된다) 매번 find+save 하는 대신 이 창 안에서는 이미 읽은 값을 그대로
     * 쓰고 쓰기(save)만 건너뛴다. 만료 여부 검사는 매번 그대로 수행되므로 보안에는 영향이 없다.
     */
    private static final long LAST_ACTIVITY_REFRESH_THRESHOLD_MS = Duration.ofMinutes(1).toMillis();

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SessionData toSessionData(Session session) {
        return SessionData.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .lastActivity(session.getLastActivity())
                .metadata(session.getMetadata())
                .build();
    }

    public SessionCreationResult createSession(String userId, SessionMetadata metadata) {
        try {
            // Remove all existing user sessions
            removeAllUserSessions(userId);

            String sessionId = generateSessionId();
            long now = Instant.now().toEpochMilli();
            
            Session session = Session.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .createdAt(now)
                    .lastActivity(now)
                    .metadata(metadata)
                    .expiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC))
                    .build();

            session = sessionStore.save(session);
            
            SessionData sessionData = toSessionData(session);

            return SessionCreationResult.builder()
                    .sessionId(sessionId)
                    .expiresIn(SESSION_TTL_SEC)
                    .sessionData(sessionData)
                    .build();

        } catch (Exception e) {
            log.error("Session creation error for userId: {}", userId, e);
            throw new RuntimeException("세션 생성 중 오류가 발생했습니다.", e);
        }
    }

    public SessionValidationResult validateSession(String userId, String sessionId) {
        try {
            if (userId == null || sessionId == null) {
                log.warn("validateSession called with null parameters: userId={}, sessionId={}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_PARAMETERS", "유효하지 않은 세션 파라미터");
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                log.warn("No session found for userId: {}", userId);
                return SessionValidationResult.invalid("INVALID_SESSION", "세션을 찾을 수 없습니다.");
            }

            if (!sessionId.equals(session.getSessionId())) {
                log.warn("Session ID mismatch for userId: {}. Provided: {}, Expected: {}", userId, sessionId, session.getSessionId());
                return SessionValidationResult.invalid("INVALID_SESSION", "잘못된 세션 ID입니다.");
            }

            // Check if session has timed out
            long now = Instant.now().toEpochMilli();
            if (now - session.getLastActivity() > SESSION_TIMEOUT) {
                log.warn("Session timed out for userId: {}, sessionId: {}", userId, sessionId);
                removeSession(userId, sessionId);
                return SessionValidationResult.invalid("SESSION_EXPIRED", "세션이 만료되었습니다.");
            }

            // 최근에 이미 갱신했으면(임계값 이내) 쓰기를 건너뛴다 — 읽은 세션을 그대로 유효 처리.
            if (now - session.getLastActivity() < LAST_ACTIVITY_REFRESH_THRESHOLD_MS) {
                return SessionValidationResult.valid(toSessionData(session));
            }

            // Update last activity
            session.setLastActivity(now);
            session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
            session = sessionStore.save(session);

            SessionData sessionData = toSessionData(session);
            return SessionValidationResult.valid(sessionData);

        } catch (Exception e) {
            log.error("Session validation error for userId: {}, sessionId: {}", userId, sessionId, e);
            return SessionValidationResult.invalid("VALIDATION_ERROR", "세션 검증 중 오류가 발생했습니다.");
        }
    }

    public void updateLastActivity(String userId) {
        try {
            if (userId == null) {
                log.warn("updateLastActivity called with null userId");
                return;
            }

            Session session = sessionStore.findByUserId(userId).orElse(null);
            if (session == null) {
                log.debug("No session found to update last activity for user: {}", userId);
                return;
            }

            session.setLastActivity(Instant.now().toEpochMilli());
            session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC));
            sessionStore.save(session);
            
        } catch (Exception e) {
            log.error("Failed to update session activity for user: {}", userId, e);
        }
    }

    public void removeSession(String userId, String sessionId) {
        try {
            if (sessionId != null) {
                sessionStore.delete(userId, sessionId);
            } else {
                sessionStore.deleteAll(userId);
            }
        } catch (Exception e) {
            log.error("Session removal error for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public void removeSession(String userId) {
        removeSession(userId, null);
    }

    public void removeAllUserSessions(String userId) {
        try {
            sessionStore.deleteAll(userId);
        } catch (Exception e) {
            log.error("Remove all sessions error for userId: {}", userId, e);
            throw new RuntimeException("모든 세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public SessionData getActiveSession(String userId) {
        try {
            Session session = sessionStore.findByUserId(userId).orElse(null);
            
            if (session == null) {
                return null;
            }

            return toSessionData(session);
        } catch (Exception e) {
            log.error("Get active session error for userId: {}", userId, e);
            return null;
        }
    }
    
}
