import { Navigate, Outlet, useMatch } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { KanbanProvider } from '../context/KanbanContext';
import { ChatProvider } from '../context/ChatContext';
import { queryClient } from '../services/queryClient';
import Header from './Header';
import Footer from './Footer';
import Chat from './Chat';

function ProtectedLayout() {
  const { token, isLoading } = useAuth();
  const { t } = useTranslation();
  const onBoard = useMatch('/board') !== null;

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
