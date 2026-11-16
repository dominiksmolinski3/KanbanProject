import { getAccessToken, getRefreshToken } from './services/session';

export const loadProtectedLayout = () => import('./components/ProtectedLayout');
export const loadBoardPage = () => import('./components/BoardPage');

// Left to the router, each of these is only asked for once the one before it has rendered.
export function startBoardCodeWhenSignedIn() {
  if (getAccessToken() || getRefreshToken()) {
    loadProtectedLayout().catch(() => {});
    loadBoardPage().catch(() => {});
  }
}
