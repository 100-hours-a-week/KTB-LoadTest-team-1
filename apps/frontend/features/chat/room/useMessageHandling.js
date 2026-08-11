import { useCallback, useEffect, useRef } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useChatFileUpload } from '../files/useChatFileUpload';
import { appendIncomingMessage } from './roomEventHandlers';

export const useMessageHandling = (
  currentUser,
  roomId,
  handleSessionError,
  messages = [],
  loadingMessages = false,
  setLoadingMessages,
  socketRef,
  setMessages,
  processedMessageIds,
) => {
  // state 체크만으로는 비동기 경쟁 조건에 취약 — ref로 즉각 잠금
  const loadingRef = useRef(false);

  useEffect(() => {
    if (!loadingMessages) {
      loadingRef.current = false;
    }
  }, [loadingMessages]);

  // handleLoadMore가 messages[0]만 필요로 하므로, 전체 배열 대신 ref로 최신 값만 추적
  // (messages를 deps에 넣으면 새 메시지가 올 때마다 콜백 참조가 바뀌어 하위 메모이제이션이 무력화됨)
  const messagesRef = useRef(messages);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

 const {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   setUploading,
   setUploadProgress,
   setUploadError,
   resetFileUpload,
   uploadChatFile
 } = useChatFileUpload();

  const getRoomSocket = useCallback(() => socketRef?.current ?? null, [socketRef]);

  const canSendOnRoomSocket = useCallback(() => {
    if (socketRef) {
      return Boolean(getRoomSocket()?.connected);
    }

    return socketClient.canSend();
  }, [getRoomSocket, socketRef]);

  const commitConfirmedMessage = useCallback((confirmedMessage) => {
    if (!confirmedMessage?._id || typeof setMessages !== 'function') return;

    processedMessageIds?.current?.add(confirmedMessage._id);
    setMessages(prev => appendIncomingMessage(prev, confirmedMessage));
  }, [processedMessageIds, setMessages]);

  const handleLoadMore = useCallback(async () => {
    if (!canSendOnRoomSocket()) {
      return;
    }

    // state 기반 체크는 비동기라 같은 tick에 두 번 진입 가능 → ref로 즉각 잠금
    if (loadingRef.current || loadingMessages) {
      return;
    }

    // messages는 이미 타임스탬프 정렬된 상태로 전달됨 → 재정렬 불필요
    const oldestMessage = messagesRef.current[0];
    const beforeTimestamp = oldestMessage?.timestamp;

    if (!beforeTimestamp) {
      return;
    }

    loadingRef.current = true;
    setLoadingMessages(true);

    try {
      // 실제 메시지 반영은 useChatRoom의 previousMessagesLoaded 상시 리스너가 처리한다.
      // 여기서는 서버가 응답을 아예 못 주는 경우(타임아웃)를 감지해 loadingMessages가
      // 영원히 true로 남아 무한 스크롤이 다시는 동작하지 않는 걸 막는 용도로만 기다린다.
      await socketClient.fetchPreviousMessagesAndWait({
        roomId: roomId,
        before: beforeTimestamp,
        limit: 30
      }, getRoomSocket());
    } catch (error) {
      loadingRef.current = false;
      setLoadingMessages(false);
      Toast.error(error.message || '이전 메시지를 불러오지 못했습니다.');
    }
  }, [roomId, loadingMessages, setLoadingMessages, canSendOnRoomSocket, getRoomSocket]);

 const handleMessageSubmit = useCallback(async (messageData) => {
   const roomSocket = getRoomSocket();
   if (!canSendOnRoomSocket() || !currentUser) {
     Toast.error('채팅 서버와 연결이 끊어졌습니다.');
     return;
   }

   if (!roomId) {
     Toast.error('채팅방 정보를 찾을 수 없습니다.');
     return;
   }

   try {
      if (messageData.type === 'file') {
        const uploadResponse = await uploadChatFile(
          messageData.fileData.file,
          currentUser
        );

       const confirmedMessage = await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'file',
         content: messageData.content || '',
         fileData: {
           _id: uploadResponse.data.file._id,
           filename: uploadResponse.data.file.filename,
           originalname: uploadResponse.data.file.originalname,
           mimetype: uploadResponse.data.file.mimetype,
           size: uploadResponse.data.file.size
         }
       }, roomSocket);
       commitConfirmedMessage(confirmedMessage);

       resetFileUpload();

     } else if (messageData.content?.trim()) {
       const confirmedMessage = await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'text',
         content: messageData.content.trim()
       }, roomSocket);
       commitConfirmedMessage(confirmedMessage);
     }

   } catch (error) {
     if (error.message?.includes('세션') ||
         error.message?.includes('인증') ||
         error.message?.includes('토큰')) {
       await handleSessionError();
       return;
     }

     // 서버가 거부한 메시지는 onError 핸들러가 이미 토스트로 알렸다.
     // 여기서 또 띄우면 같은 사유의 토스트가 두 개 뜬다.
     if (error?.code !== 'MESSAGE_REJECTED') {
       Toast.error(error.message || '메시지 전송 중 오류가 발생했습니다.');
     }
     if (messageData.type === 'file') {
       setUploadError(error.message);
       setUploading(false);
     }
   }
 }, [currentUser, roomId, handleSessionError, uploadChatFile, resetFileUpload, setUploadError, setUploading, canSendOnRoomSocket, getRoomSocket, commitConfirmedMessage]);

 const removeFilePreview = useCallback(() => {
   resetFileUpload();
 }, [resetFileUpload]);

 return {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   handleMessageSubmit,
   handleLoadMore,
   removeFilePreview,
 };
};

export default useMessageHandling;
