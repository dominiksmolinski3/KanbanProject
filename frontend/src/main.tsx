import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/components/BoardTokens.css';
import './styles/index.css';
import App from './App';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import './i18n';

const root = document.getElementById('root');
if (!root) {
  throw new Error('index.html has no #root element');
}

createRoot(root).render(
  <StrictMode>
    <App />
    <ToastContainer position="bottom-right" autoClose={3000} />
  </StrictMode>,
);
