import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { KanbanProvider } from '../context/KanbanContext';
import { ChatProvider } from '../context/ChatContext';
import Header from './Header';
import Footer from './Footer';
import Chat from './Chat';

// The shell every authenticated screen shares - one copy instead of one per route, so a change
// here is one edit rather than four. `Outlet` is where the matched child route renders.
function ProtectedLayout() {
  const { token, isLoading } = useAuth();
  const { t } = useTranslation();

  if (isLoading) {
    return <div>{t('auth.checkingSession')}</div>;
  }

  if (!token) {
    return <Navigate to="/" replace />;
  }

  return (
    <KanbanProvider>
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
  );
}

export default ProtectedLayout;
