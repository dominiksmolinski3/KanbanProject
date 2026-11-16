const loaded = [];

jest.mock('../components/ProtectedLayout', () => {
  loaded.push('ProtectedLayout');
  return { __esModule: true, default: () => null };
});
jest.mock('../components/BoardPage', () => {
  loaded.push('BoardPage');
  return { __esModule: true, default: () => null };
});

describe('starting the board code with the page', () => {
  let startBoardCodeWhenSignedIn;

  beforeEach(() => {
    loaded.length = 0;
    localStorage.clear();
    jest.resetModules();
    ({ startBoardCodeWhenSignedIn } = require('../boardCode'));
  });

  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('asks for the layout and the board together when there is a session', async () => {
    localStorage.setItem('token', 'jwt');

    startBoardCodeWhenSignedIn();
    await settle();

    expect(loaded.sort()).toEqual(['BoardPage', 'ProtectedLayout']);
  });

  it('does the same for a session whose access token is gone but can be renewed', async () => {
    localStorage.setItem('refreshToken', 'renew');

    startBoardCodeWhenSignedIn();
    await settle();

    expect(loaded.sort()).toEqual(['BoardPage', 'ProtectedLayout']);
  });

  it('asks for nothing on the sign-in screen, which has its own download budget', async () => {
    startBoardCodeWhenSignedIn();
    await settle();

    expect(loaded).toEqual([]);
  });
});
