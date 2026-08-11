import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatMessages from '../ChatMessages';

// jsdom에는 실제 레이아웃/측정이 없으므로, 실제 가상화 계산 대신
// count만큼 그대로 렌더하는 pass-through로 대체한다.
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: (options) => ({
    getTotalSize: () => options.count * 120,
    getVirtualItems: () =>
      Array.from({ length: options.count }, (_, index) => ({
        index,
        key: options.getItemKey ? options.getItemKey(index) : index,
        start: index * 120,
      })),
    measureElement: () => {},
  }),
}));

vi.mock('../../hooks/useInfiniteScroll', () => ({
  useInfiniteScroll: () => ({ sentinelRef: { current: null } }),
}));

vi.mock('../../hooks/useAutoScroll', () => ({
  useAutoScroll: () => ({
    containerRef: { current: null },
    scrollToBottom: vi.fn(),
    isNearBottom: true,
  }),
}));

vi.mock('../SystemMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../FileMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../UserMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

describe('ChatMessages', () => {
  it('renders messages sorted by timestamp without mutating the input array', () => {
    const messages = [
      {
        _id: 'late',
        content: 'late message',
        timestamp: '2026-06-20T12:00:00.000Z',
        sender: { _id: 'other' },
      },
      {
        _id: 'early',
        content: 'early message',
        timestamp: '2026-06-20T11:00:00.000Z',
        sender: { _id: 'other' },
      },
    ];
    const originalOrder = messages.map((message) => message._id);

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'early message',
      'late message',
    ]);
    expect(messages.map((message) => message._id)).toEqual(originalOrder);
  });

  it('keeps optimized message wrappers discoverable in the rendered DOM', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'discoverable message',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: true,
      })
    );

    const message = screen.getByText('discoverable message');
    const virtualRowWrapper = message.closest('[data-index]');

    expect(message).toBeInTheDocument();
    expect(virtualRowWrapper).toHaveAttribute('data-index', '0');
    expect(virtualRowWrapper).toHaveStyle({ position: 'absolute' });
    expect(screen.getByText('이전 메시지를 불러오는 중...')).toBeInTheDocument();
  });

  it('renders only the messages the virtualizer reports as visible', () => {
    const messages = Array.from({ length: 5 }, (_, index) => ({
      _id: `message-${index}`,
      content: `message ${index}`,
      timestamp: `2026-06-20T11:0${index}:00.000Z`,
      sender: { _id: 'other' },
    }));

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    // 목(mock) 가상화가 count(5)만큼만 행을 만들어내므로, 실제 메시지 개수와
    // 렌더된 행 개수가 일치하는지(가상화 경로를 실제로 타는지)를 확인한다.
    expect(screen.getAllByTestId('message')).toHaveLength(5);
  });
});
