import { useEffect } from 'react';
import { Navigate, Outlet, useMatch } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { KanbanProvider } from '../context/KanbanContext';
import { ChatProvider } from '../context/ChatContext';
import { queryClient } from '../services/queryClient';
import { getSessionId } from '../services/session';
import { attachThisBrowser } from '../crypto/attach';
import Header from './Header';
import Footer from './Footer';
import Chat from './Chat';

function ProtectedLayout() {
  const { token, isLoading, user } = useAuth();
  const { t } = useTranslation();
  const onBoard = useMatch('/board') !== null;
  const userId = user?.id;

  useEffect(() => {
    if (userId !== undefined) {
      void attachThisBrowser(Number(getSessionId()) || null, userId);
    }
  }, [userId]);

  if (isLoading) {
    return <div>{t('auth.checkingSession')}</div>;
  }

  if (!token) {
    return <Navigate to="/" replace />;
  }

  return (
    <QueryClientProvider client={queryClient}>
      <KanbanProvider boardContents={onBoard}>
        <ChatProvider>
          <div className="app-container">
            <Header />
            <div className="content-container">
              <Outlet />
            </div>
            <Footer />
            <Chat />
          </div>
        </ChatProvider>
      </KanbanProvider>
    </QueryClientProvider>
  );
}

export default ProtectedLayout;
