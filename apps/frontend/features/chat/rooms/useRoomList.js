import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

const ROOMS_PAGE_SIZE = 30;

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);

  const isLoadingRef = useRef(false);
  const pageRef = useRef(0);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });

    setConnectionStatus(CONNECTION_STATUS.ERROR);
  }, [isRetrying, setConnectionStatus]);

  // page=0부터 시작하는 서버 페이지네이션 조회. 목록을 처음부터 다시 그릴 때(초기 로드/
  // 새로고침)만 쓴다 — 다음 페이지를 이어붙이는 건 loadMoreRooms가 따로 담당한다.
  const loadRooms = useCallback(async (page = 0) => {
    const response = await axiosInstance.get('/api/rooms', {
      params: { page, size: ROOMS_PAGE_SIZE },
    });

    if (!response?.data?.data) {
      throw new Error('INVALID_RESPONSE');
    }

    return {
      rooms: response.data.data,
      hasMore: Boolean(response.data.metadata?.hasMore),
    };
  }, []);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current) {
      return;
    }

    try {
      isLoadingRef.current = true;

      setLoading(true);
      setError(null);

      const { rooms: loaded, hasMore: more } = await loadRooms(0);
      pageRef.current = 0;
      setRooms(loaded);
      setHasMore(more);

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoading(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, isInitialLoad, loadRooms, handleFetchError]);

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다(첫 페이지부터 다시).
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({ silent = false } = {}) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setRefreshing(true);

      const { rooms: loaded, hasMore: more } = await loadRooms(0);
      pageRef.current = 0;
      setRooms(loaded);
      setHasMore(more);
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, loadRooms]);

  // 다음 페이지를 뒤에 이어붙인다. 실패해도 pageRef는 증가시키지 않아 다음 클릭에서
  // 같은 페이지를 다시 시도한다.
  const loadMoreRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current || !hasMore) {
      return;
    }

    try {
      isLoadingRef.current = true;
      setLoadingMore(true);

      const nextPage = pageRef.current + 1;
      const { rooms: loaded, hasMore: more } = await loadRooms(nextPage);
      pageRef.current = nextPage;
      setRooms((prev) => [...prev, ...loaded]);
      setHasMore(more);
    } catch (error) {
      setError({
        title: '채팅방 목록을 더 불러오지 못했습니다',
        message: '잠시 후 다시 시도해주세요.',
        type: 'warning',
        showRetry: false,
      });
    } finally {
      setLoadingMore(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, hasMore, loadRooms]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      } else {
        setError({
          title: '채팅방 입장 실패',
          message: '채팅방 입장에 실패했습니다.',
          type: 'danger',
        });
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    loading,
    refreshing,
    loadingMore,
    hasMore,
    joiningRoom,
    fetchRooms,
    refreshRooms,
    loadMoreRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
