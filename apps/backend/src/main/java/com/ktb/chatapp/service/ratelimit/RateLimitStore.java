package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.RateLimitCheckResult;
import java.util.Optional;

/**
 * Data store interface for rate limit storage.
 * Provides operations for storing and retrieving rate limit data.
 */
public interface RateLimitStore {

    /**
     * Find rate limit by client ID
     *
     * @param clientId the client identifier
     * @return Optional containing the RateLimit if found, empty otherwise
     */
    Optional<RateLimit> findByClientId(String clientId);

    /**
     * Save or update rate limit
     *
     * @param rateLimit the rate limit to save
     * @return the saved rate limit
     */
    RateLimit save(RateLimit rateLimit);

    /**
     * 지원하는 저장소면 카운트 확인+증가를 원자적으로 한 번에 처리하고 결과를 반환한다. 이게 있으면
     * RateLimitService는 이걸 우선 쓰고, {@link Optional#empty()}면(기본, Mongo 등) 기존
     * find+check+save 조합으로 폴백한다 — 기본 구현이 항상 empty라서 이 메서드를 오버라이드하지 않는
     * 저장소는 동작이 전혀 안 바뀐다.
     *
     * @param clientId 저장소 키로 쓰일 clientId (호스트 프리픽스까지 포함해서 호출부가 넘김)
     * @param maxRequests 허용되는 최대 요청 수
     * @param windowSeconds 시간 윈도우(초, 1 이상으로 정규화되어 넘어옴)
     */
    default Optional<RateLimitCheckResult> tryAtomicCheckAndIncrement(
            String clientId, int maxRequests, long windowSeconds) {
        return Optional.empty();
    }
}
