package com.ktb.chatapp.repository;

/**
 * 방별 최근 메시지 수 집계 결과 프로젝션. {@code _id}가 room ID로 매핑된다.
 */
public interface RoomMessageCount {
    String getId();
    long getCount();
}
