import React, { lazy, Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { setupApiInterceptors } from './services/apiInterceptor';
import HomePage from './components/HomePage';
import NotFound from './components/NotFound';
import PageLoading from './components/PageLoading';
import './styles/App.css';

const ProtectedLayout = lazy(() => import('./components/ProtectedLayout'));
const BoardPage = lazy(() => import('./components/BoardPage'));
const UsersManagement = lazy(() => import('./components/UsersManagement'));
const ActivityFeed = lazy(() => import('./components/ActivityFeed'));
const FlowMetrics = lazy(() => import('./components/FlowMetrics'));
const Devices = lazy(() => import('./components/Devices'));

setupApiInterceptors();

function App() {
  return (
    <AuthProvider>
      <Router>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route
            element={
              <Suspense fallback={<PageLoading />}>
                <ProtectedLayout />
              </Suspense>
            }
          >
            <Route path="/board" element={<Suspense fallback={<PageLoading />}><BoardPage /></Suspense>} />
            <Route path="/users" element={<Suspense fallback={<PageLoading />}><UsersManagement /></Suspense>} />
            <Route path="/activity" element={<Suspense fallback={<PageLoading />}><ActivityFeed /></Suspense>} />
            <Route path="/flow" element={<Suspense fallback={<PageLoading />}><FlowMetrics /></Suspense>} />
            <Route path="/sessions" element={<Suspense fallback={<PageLoading />}><Devices /></Suspense>} />
          </Route>
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Router>
    </AuthProvider>
  );
}

export default App;
