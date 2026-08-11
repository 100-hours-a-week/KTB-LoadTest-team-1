import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import fileService from '@/services/fileService';
import { useMessageHandling } from '../useMessageHandling';

vi.mock('@/components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
  default: () => null,
}));

vi.mock('@/services/fileService', () => ({
  default: {
    uploadFile: vi.fn(),
  },
}));

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    sendChatMessageAndWait: vi.fn(),
    fetchPreviousMessages: vi.fn(),
    fetchPreviousMessagesAndWait: vi.fn(),
  },
}));

const roomId = 'room-1';

const currentUser = {
  token: 'token-1',
  sessionId: 'session-1',
};

describe('useMessageHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  it('sends trimmed text message through the subscribed room socket', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    await act(async () => {
      await result.current.handleMessageSubmit({ content: '  hello  ' });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledWith(
      {
        room: 'room-1',
        type: 'text',
        content: 'hello',
      },
      roomSocket,
    );
  });

  it('shows a connection error without emitting when disconnected', async () => {
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn())
    );

    await act(async () => {
      await result.current.handleMessageSubmit({ content: 'hello' });
    });

    expect(socketClient.sendChatMessageAndWait).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('채팅 서버와 연결이 끊어졌습니다.');
  });

  it('uploads files, sends file messages, and clears file preview state', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    fileService.uploadFile.mockResolvedValue({
      success: true,
      data: {
        file: {
          _id: 'file-1',
          filename: 'stored.pdf',
          originalname: 'sample.pdf',
          mimetype: 'application/pdf',
          size: 128,
        },
      },
    });
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    await act(async () => {
      result.current.setFilePreview({ name: 'sample.pdf' });
      await result.current.handleMessageSubmit({
        type: 'file',
        content: 'attached',
        fileData: {
          file: { name: 'sample.pdf' },
        },
      });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledWith(
      {
        room: 'room-1',
        type: 'file',
        content: 'attached',
        fileData: {
          _id: 'file-1',
          filename: 'stored.pdf',
          originalname: 'sample.pdf',
          mimetype: 'application/pdf',
          size: 128,
        },
      },
      roomSocket,
    );
    expect(result.current.filePreview).toBeNull();
    expect(result.current.uploadError).toBeNull();
  });

  it('requests older messages before the oldest loaded message', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    const setLoadingMessages = vi.fn();
    socketClient.fetchPreviousMessagesAndWait.mockResolvedValue({ messages: [], hasMore: false });
    const messages = [{ timestamp: '2024-01-01T00:00:00.000Z' }];
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), messages, false, setLoadingMessages, socketRef)
    );

    await act(async () => {
      await result.current.handleLoadMore();
    });

    expect(socketClient.fetchPreviousMessagesAndWait).toHaveBeenCalledWith(
      { roomId, before: messages[0].timestamp, limit: 30 },
      roomSocket,
    );
    expect(setLoadingMessages).toHaveBeenCalledWith(true);
  });

  it('releases the loading lock and surfaces an error when the server never responds', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    const setLoadingMessages = vi.fn();
    socketClient.fetchPreviousMessagesAndWait.mockRejectedValue(
      new Error('메시지 로딩 시간이 초과되었습니다.')
    );
    const messages = [{ timestamp: '2024-01-01T00:00:00.000Z' }];
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), messages, false, setLoadingMessages, socketRef)
    );

    await act(async () => {
      await result.current.handleLoadMore();
    });

    // 타임아웃이 나도 loadingMessages가 false로 풀려야 무한 스크롤이 다시 시도된다.
    expect(setLoadingMessages).toHaveBeenLastCalledWith(false);
    expect(Toast.error).toHaveBeenCalledWith('메시지 로딩 시간이 초과되었습니다.');
  });

  it('does not start another load while one is already in flight', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    const messages = [{ timestamp: '2024-01-01T00:00:00.000Z' }];
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), messages, true, vi.fn(), socketRef)
    );

    await act(async () => {
      await result.current.handleLoadMore();
    });

    expect(socketClient.fetchPreviousMessagesAndWait).not.toHaveBeenCalled();
  });
});
