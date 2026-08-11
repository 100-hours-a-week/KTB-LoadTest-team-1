import React from 'react';
import dynamic from 'next/dynamic';
import { useRouter } from 'next/router';
import { ThemeProvider } from '@vapor-ui/core';
import '@vapor-ui/core/styles.css';
import '../styles/globals.css';
import { AuthProvider, useAuth } from '@/contexts/AuthContext';

// 로그인/회원가입은 Artillery를 포함해 새 브라우저가 가장 먼저 방문하는 경로다.
// 채팅 전용 UI와 Socket.IO를 공통 번들에 넣지 않고 인증 이후에만 내려받는다.
const ChatHeader = dynamic(() => import('@/components/ChatHeader'), { ssr: false });
const ToastContainer = dynamic(() => import('@/components/Toast'), { ssr: false });
const SocketProvider = dynamic(
  () => import('@/lib/socket/SocketProvider').then((module) => module.SocketProvider),
  { ssr: false },
);

const AuthenticatedApp = ({ children, showChrome }) => {
  const { user } = useAuth();

  if (!showChrome) {
    return children;
  }

  return (
    <SocketProvider session={user}>
      <ChatHeader />
      {children}
      <ToastContainer />
    </SocketProvider>
  );
};

function MyApp({ Component, pageProps }) {
  const router = useRouter();

  const isErrorPage = router.pathname === '/_error';
  if (isErrorPage) {
    return <Component {...pageProps} />;
  }

  // 인증 화면은 채팅 전용 chrome과 socket 번들을 로드하지 않는다.
  const showChrome = !['/', '/login', '/register'].includes(router.pathname);

  return (
    <ThemeProvider defaultTheme="dark">
      <AuthProvider>
        <AuthenticatedApp showChrome={showChrome}>
          <Component {...pageProps} />
        </AuthenticatedApp>
      </AuthProvider>
    </ThemeProvider>
  );
}

export default MyApp;
