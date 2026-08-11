import { useCallback, useState } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';

export const useReactionHandling = ({ currentUser, setMessages }) => {
  const [pendingReactions] = useState(new Map());

  const handleReactionAdd = useCallback(async (messageId, reaction) => {
    let previousReactions;
    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트 (롤백용 원본 reactions를 이 시점에 캡처)
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            previousReactions = msg.reactions || {};
            const currentUsers = previousReactions[reaction] || [];

            // 중복 추가 방지
            if (!currentUsers.includes(currentUser.id)) {
              return {
                ...msg,
                reactions: {
                  ...previousReactions,
                  [reaction]: [...currentUsers, currentUser.id]
                }
              };
            }
          }
          return msg;
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'add');

    } catch (error) {
      console.error('Add reaction error:', error);
      Toast.error('리액션 추가에 실패했습니다.');

      // 실패 시 롤백
      setMessages(prevMessages =>
        prevMessages.map(msg =>
          msg._id === messageId ?
          { ...msg, reactions: previousReactions ?? {} } :
          msg
        )
      );
    }
  }, [currentUser, setMessages]);

  const handleReactionRemove = useCallback(async (messageId, reaction) => {
    let previousReactions;
    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트 (롤백용 원본 reactions를 이 시점에 캡처)
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            previousReactions = msg.reactions || {};
            const currentUsers = previousReactions[reaction] || [];
            return {
              ...msg,
              reactions: {
                ...previousReactions,
                [reaction]: currentUsers.filter(id => id !== currentUser.id)
              }
            };
          }
          return msg;
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'remove');

    } catch (error) {
      console.error('Remove reaction error:', error);
      Toast.error('리액션 제거에 실패했습니다.');

      // 실패 시 롤백
      setMessages(prevMessages =>
        prevMessages.map(msg =>
          msg._id === messageId ?
          { ...msg, reactions: previousReactions ?? {} } :
          msg
        )
      );
    }
  }, [currentUser, setMessages]);

  const handleReactionUpdate = useCallback(({ messageId, reactions }) => {
    setMessages(prevMessages =>
      prevMessages.map(msg =>
        msg._id === messageId ? { ...msg, reactions } : msg
      )
    );
  }, [setMessages]);

  return {
    handleReactionAdd,
    handleReactionRemove,
    handleReactionUpdate
  };
};

export default useReactionHandling;
