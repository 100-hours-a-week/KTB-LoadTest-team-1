import React, { useCallback, useMemo, useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Spinner, Text, VStack } from '@vapor-ui/core';
import SystemMessage from './SystemMessage';
import FileMessage from './FileMessage';
import UserMessage from './UserMessage';
import { useInfiniteScroll } from '../hooks/useInfiniteScroll';
import { useAutoScroll } from '../hooks/useAutoScroll';
import { getTime, isSortedAscending } from '@/features/chat/messages/useMessageList';

// 텍스트/이미지/파일이 섞인 메시지의 평균적인 높이 추정치. 실제 높이는
// 가상화가 measureElement로 렌더 후 다시 실측해 보정한다.
const ESTIMATED_MESSAGE_HEIGHT = 120;

const LoadingIndicator = React.memo(() => (
  <div className="loading-messages">
    <Spinner size="md" colorPalette="primary" aria-label="이전 메시지 로딩 중" />
    <span className="text-secondary text-sm">이전 메시지를 불러오는 중...</span>
  </div>
));
LoadingIndicator.displayName = 'LoadingIndicator';

const MessageHistoryEnd = React.memo(() => (
  <div className="text-center p-2 mb-4" data-testid="message-history-end">
    <Text typography="body2" foreground="hint-100">더 이상 불러올 메시지가 없습니다.</Text>
  </div>
));
MessageHistoryEnd.displayName = 'MessageHistoryEnd';

const EmptyMessages = React.memo(() => (
  <div className="empty-messages">
    <Text typography="body1">아직 메시지가 없습니다.</Text>
    <Text typography="body2" foreground="hint-100">첫 메시지를 보내보세요!</Text>
  </div>
));
EmptyMessages.displayName = 'EmptyMessages';

const ChatMessages = ({
  messages = [],
  currentUser = null,
  room = null,
  loadingMessages = false,
  hasMoreMessages = true,
  onReactionAdd = () => {},
  onReactionRemove = () => {},
  onLoadMore = () => {}
}) => {
  // 무한 스크롤 훅
  const { sentinelRef } = useInfiniteScroll(
    onLoadMore,
    hasMoreMessages,
    loadingMessages
  );

  // rowVirtualizer는 containerRef가 필요하고, useAutoScroll의 scrollToBottom은
  // rowVirtualizer.scrollToIndex가 필요한 순환 관계라 ref로 간접 연결한다.
  const virtualizerRef = useRef(null);
  const scrollToIndex = useCallback((index, opts) => {
    virtualizerRef.current?.scrollToIndex(index, opts);
  }, []);

  // 자동 스크롤 훅 (스크롤 복원 기능 포함)
  const { containerRef, scrollToBottom, isNearBottom } = useAutoScroll(
    messages,
    currentUser?.id,
    loadingMessages,
    100, // 하단 100px 이내면 자동 스크롤
    scrollToIndex
  );
  const isMine = useCallback((msg) => {
    if (!msg?.sender || !currentUser?.id) return false;
    
    return (
      msg.sender._id === currentUser.id || 
      msg.sender.id === currentUser.id ||
      msg.sender === currentUser.id
    );
  }, [currentUser?.id]);

  const allMessages = useMemo(() => {
    if (!Array.isArray(messages)) return [];

    // messages는 상위에서 이미 타임스탬프 오름차순으로 관리되므로,
    // 어긋난 경우에만 방어적으로 정렬한다 (매 렌더마다 전체 재정렬을 피함).
    return isSortedAscending(messages)
      ? messages
      : [...messages].sort((a, b) => getTime(a) - getTime(b));
  }, [messages]);

  const rowVirtualizer = useVirtualizer({
    count: allMessages.length,
    getScrollElement: () => containerRef.current,
    estimateSize: () => ESTIMATED_MESSAGE_HEIGHT,
    overscan: 8,
    getItemKey: (index) => allMessages[index]?._id ?? index,
  });
  virtualizerRef.current = rowVirtualizer;

  const renderMessage = useCallback((msg) => {
    if (!msg) return null;

    const commonProps = {
      currentUser,
      room,
      onReactionAdd,
      onReactionRemove
    };

    const MessageComponent = {
      system: SystemMessage,
      file: FileMessage
    }[msg.type] || UserMessage;

    return (
      <MessageComponent
        {...commonProps}
        msg={msg}
        content={msg.content}
        isMine={msg.type !== 'system' ? isMine(msg) : undefined}
        isStreaming={msg.type === 'ai' ? (msg.isStreaming || false) : undefined}
      />
    );
  }, [currentUser, room, isMine, onReactionAdd, onReactionRemove]);

  return (
    <VStack
      ref={containerRef}
      className="h-full overflow-y-auto overflow-x-hidden scroll-smooth [overflow-scrolling:touch]"
      $css={{
        gap: '$200',
        padding: '$300',
      }}
      role="log"
      aria-live="polite"
      aria-atomic="false"
      data-testid="chat-messages-container"
    >
      {/* Sentinel 요소 - 스크롤 맨 위에 배치하여 위로 스크롤 시 이전 메시지 로드 */}
      {hasMoreMessages && (
        <div
          ref={sentinelRef}
          style={{
            height: '20px',
            margin: '10px 0',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center'
          }}
        >
          {loadingMessages && <LoadingIndicator />}
        </div>
      )}

      {!hasMoreMessages && messages.length > 0 && (
        <MessageHistoryEnd />
      )}

      {allMessages.length === 0 ? (
        <EmptyMessages />
      ) : (
        <div
          style={{
            position: 'relative',
            width: '100%',
            height: rowVirtualizer.getTotalSize(),
          }}
        >
          {rowVirtualizer.getVirtualItems().map((virtualRow) => (
            <div
              key={virtualRow.key}
              data-index={virtualRow.index}
              ref={rowVirtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                // VStack의 flex gap은 절대 위치 지정된 행 사이에는 적용되지 않으므로,
                // 같은 간격(gap: '$200')을 행 안쪽 padding으로 대신 준다.
                paddingBottom: 'var(--vapor-space-200)',
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              {renderMessage(allMessages[virtualRow.index])}
            </div>
          ))}
        </div>
      )}
    </VStack>
  );
};

ChatMessages.displayName = 'ChatMessages';

export default React.memo(ChatMessages);
