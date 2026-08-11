import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatRoomsView from '../ChatRoomsView';
import { CONNECTION_STATUS } from '../useServerConnection';

const mocks = vi.hoisted(() => ({
  connectionStatus: 'checking',
  error: null,
  rooms: [],
  fetchRooms: vi.fn(() => Promise.resolve()),
  refreshRooms: vi.fn(() => Promise.resolve(true)),
  attemptConnection: vi.fn(() => Promise.resolve(true)),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'user-1',
      token: 'token-1',
      sessionId: 'session-1',
    },
  }),
}));

vi.mock('../useServerConnection', async () => {
  const actual = await vi.importActual('../useServerConnection');
  return {
    ...actual,
    useServerConnection: () => ({
      connectionStatus: mocks.connectionStatus,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: mocks.attemptConnection,
    }),
  };
});

vi.mock('../useRoomList', () => ({
  useRoomList: () => ({
    rooms: mocks.rooms,
    setRooms: vi.fn(),
    error: mocks.error,
    loading: false,
    refreshing: false,
    joiningRoom: false,
    fetchRooms: mocks.fetchRooms,
    refreshRooms: mocks.refreshRooms,
    handleJoinRoom: vi.fn(),
  }),
}));

vi.mock('../useRoomsSocket', () => ({
  useRoomsSocket: vi.fn(),
}));

describe('ChatRoomsView', () => {
  beforeEach(() => {
    mocks.connectionStatus = CONNECTION_STATUS.CHECKING;
    mocks.error = null;
    mocks.rooms = [];
    mocks.fetchRooms.mockClear();
    mocks.refreshRooms.mockClear();
    mocks.attemptConnection.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not refetch rooms when connection status changes after the initial load starts', async () => {
    const { rerender } = render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await waitFor(() => {
      expect(mocks.fetchRooms).toHaveBeenCalledTimes(1);
    });

    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    rerender(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mocks.fetchRooms).toHaveBeenCalledTimes(1);
  });

  it('refreshes the room list on an interval while connected', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    vi.useFakeTimers();

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await vi.advanceTimersByTimeAsync(30000);

    expect(mocks.refreshRooms).toHaveBeenCalledWith({ silent: true });
  });

  it('does not auto refresh while the server connection is not established', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.DISCONNECTED;
    vi.useFakeTimers();

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await vi.advanceTimersByTimeAsync(90000);

    expect(mocks.refreshRooms).not.toHaveBeenCalled();
  });

  it('catches up as soon as the tab becomes visible again', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await waitFor(() => {
      expect(mocks.fetchRooms).toHaveBeenCalled();
    });

    document.dispatchEvent(new Event('visibilitychange'));

    expect(mocks.refreshRooms).toHaveBeenCalledWith({ silent: true });
  });

  it('refreshes the list when the refresh button is clicked', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    fireEvent.click(await screen.findByTestId('refresh-rooms-button'));

    expect(mocks.refreshRooms).toHaveBeenCalledTimes(1);
    expect(mocks.refreshRooms).toHaveBeenCalledWith();
  });

  it('offers reconnect instead of refresh while an error is shown', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.ERROR;
    mocks.error = { title: '연결 오류', message: '서버와 연결할 수 없습니다.', type: 'danger' };

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch: vi.fn() }} />);

    await waitFor(() => {
      expect(screen.getByText('재연결')).toBeTruthy();
    });

    expect(screen.queryByTestId('refresh-rooms-button')).toBeNull();
  });

  it('does not prefetch rooms until the join button is hovered or focused', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    const createdAt = '2026-08-11T00:00:00.000Z';
    mocks.rooms = [
      { _id: 'room-1', name: '방 1', participants: [], createdAt },
      { _id: 'room-2', name: '방 2', participants: [], createdAt },
    ];
    const prefetch = vi.fn();

    render(<ChatRoomsView router={{ push: vi.fn(), prefetch }} />);

    const buttons = await screen.findAllByTestId('join-chat-room-button');
    expect(buttons).toHaveLength(2);

    // 목록이 뜬 시점에는 아무 방도 prefetch하지 않는다 — 실제로 입장할 방 1개만
    // hover/focus 시점에 prefetch해서 부하 상황에서 불필요한 요청을 줄인다.
    expect(prefetch).not.toHaveBeenCalled();

    fireEvent.mouseEnter(buttons[0]);
    expect(prefetch).toHaveBeenCalledWith('/chat/room-1');
    expect(prefetch).toHaveBeenCalledTimes(1);

    // 같은 방을 다시 hover해도 중복 요청은 쏘지 않는다.
    fireEvent.mouseEnter(buttons[0]);
    expect(prefetch).toHaveBeenCalledTimes(1);

    fireEvent.focus(buttons[1]);
    expect(prefetch).toHaveBeenCalledWith('/chat/room-2');
    expect(prefetch).toHaveBeenCalledTimes(2);
  });
});
