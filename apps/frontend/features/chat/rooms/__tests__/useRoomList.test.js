import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms, metadata = {}) => ({ data: { data: rooms, metadata } });

const renderRoomList = () =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router: { push: vi.fn() },
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('surfaces an error and does not navigate when the join response reports failure', async () => {
    axiosInstance.post.mockResolvedValue({ data: { success: false } });

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.handleJoinRoom('room-1');
    });

    expect(result.current.error).toMatchObject({ title: '채팅방 입장 실패' });
    expect(result.current.joiningRoom).toBe(false);
  });

  it('requests page 0 and exposes hasMore from the response metadata', async () => {
    axiosInstance.get.mockResolvedValue(
      roomsResponse([{ _id: 'room-1' }], { hasMore: true })
    );

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    expect(axiosInstance.get).toHaveBeenCalledWith('/api/rooms', {
      params: { page: 0, size: 30 },
    });
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.hasMore).toBe(true);
  });

  it('appends the next page instead of replacing the current list', async () => {
    axiosInstance.get.mockResolvedValueOnce(
      roomsResponse([{ _id: 'room-1' }], { hasMore: true })
    );

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockResolvedValueOnce(
      roomsResponse([{ _id: 'room-2' }], { hasMore: false })
    );

    await act(async () => {
      await result.current.loadMoreRooms();
    });

    expect(axiosInstance.get).toHaveBeenLastCalledWith('/api/rooms', {
      params: { page: 1, size: 30 },
    });
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }, { _id: 'room-2' }]);
    expect(result.current.hasMore).toBe(false);
  });

  it('does not request another page when hasMore is false', async () => {
    axiosInstance.get.mockResolvedValue(
      roomsResponse([{ _id: 'room-1' }], { hasMore: false })
    );

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockClear();

    await act(async () => {
      await result.current.loadMoreRooms();
    });

    expect(axiosInstance.get).not.toHaveBeenCalled();
  });
});
