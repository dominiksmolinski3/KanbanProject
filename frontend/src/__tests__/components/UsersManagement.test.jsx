import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { toast } from 'react-toastify';
import UsersManagement from '../../components/UsersManagement';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));
jest.mock('../../components/BoardMembers', () => () => <div>BoardMembers</div>);
jest.mock('../../components/Invitations', () => () => <div>Invitations</div>);

const USERS = [
  { id: 1, name: 'Ada Lovelace', email: 'ada@example.com' },
  { id: 2, name: 'Bob Marsh', email: 'bob@example.com' },
];

const jsonResponse = (body, ok = true) => ({
  ok, status: ok ? 200 : 500, json: async () => body, text: async () => JSON.stringify(body),
});
const blobResponse = (ok = true) => ({ ok, status: ok ? 200 : 404, blob: async () => new Blob(['x']) });

describe('UsersManagement', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    global.URL.createObjectURL = jest.fn(() => 'blob:mock-url');
    global.URL.revokeObjectURL = jest.fn();
    // A failed avatar fetch is expected in most of these cases and warns by design (see
    // fetchUserAvatar); silenced so the suite's output isn't dominated by it.
    jest.spyOn(console, 'warn').mockImplementation(() => {});
  });

  test('loads the roster and fetches each avatar', async () => {
    global.fetch = jest.fn((url) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse());
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<UsersManagement />);

    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());
    expect(screen.getByText('Bob Marsh')).toBeInTheDocument();
    expect(screen.getByText('BoardMembers')).toBeInTheDocument();
    expect(screen.getByText('Invitations')).toBeInTheDocument();
    expect(global.fetch).toHaveBeenCalledWith('/api/users/1/avatar', expect.any(Object));
  });

  test('a failed roster load reports the error and shows nobody', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: false }));

    render(<UsersManagement />);

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('usersManagement.messages.loadError'));
    expect(screen.queryByText('Ada Lovelace')).not.toBeInTheDocument();
  });

  test('deleting a user: declining the confirm sends no request', async () => {
    global.fetch = jest.fn((url) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      throw new Error(`unexpected fetch ${url}`);
    });
    jest.spyOn(window, 'confirm').mockReturnValue(false);

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());
    global.fetch.mockClear();

    fireEvent.click(screen.getAllByTitle('usersManagement.buttons.delete')[0]);

    expect(global.fetch).not.toHaveBeenCalled();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  test('deleting a user: confirming removes them from the list on success', async () => {
    global.fetch = jest.fn((url, options) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      if (url === '/api/users/1' && options?.method === 'DELETE') return Promise.resolve({ ok: true });
      throw new Error(`unexpected fetch ${url}`);
    });
    jest.spyOn(window, 'confirm').mockReturnValue(true);

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());

    fireEvent.click(screen.getAllByTitle('usersManagement.buttons.delete')[0]);

    await waitFor(() => expect(screen.queryByText('Ada Lovelace')).not.toBeInTheDocument());
    expect(screen.getByText('Bob Marsh')).toBeInTheDocument();
  });

  test('deleting a user: a server refusal reports the error and keeps them listed', async () => {
    global.fetch = jest.fn((url, options) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      if (url === '/api/users/1' && options?.method === 'DELETE') return Promise.resolve({ ok: false, status: 500 });
      throw new Error(`unexpected fetch ${url}`);
    });
    jest.spyOn(window, 'confirm').mockReturnValue(true);
    jest.spyOn(console, 'error').mockImplementation(() => {});

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());

    fireEvent.click(screen.getAllByTitle('usersManagement.buttons.delete')[0]);

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('usersManagement.messages.deleteError'));
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  test('avatar upload: a file over 10MB is rejected client-side', async () => {
    global.fetch = jest.fn((url) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());
    global.fetch.mockClear();

    const bigFile = new File([new ArrayBuffer(11 * 1024 * 1024)], 'big.png', { type: 'image/png' });
    const input = document.getElementById('avatar-input-1');
    fireEvent.change(input, { target: { files: [bigFile] } });

    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('usersManagement.messages.fileTooLarge'));
    expect(global.fetch).not.toHaveBeenCalled();
  });

  test('avatar upload: an unsupported type is rejected client-side', async () => {
    global.fetch = jest.fn((url) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());
    global.fetch.mockClear();

    const badFile = new File(['x'], 'file.gif', { type: 'image/gif' });
    const input = document.getElementById('avatar-input-1');
    fireEvent.change(input, { target: { files: [badFile] } });

    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('usersManagement.messages.fileTypeError'));
    expect(global.fetch).not.toHaveBeenCalled();
  });

  test('avatar upload: a valid file posts, then refetches the avatar and confirms', async () => {
    global.fetch = jest.fn((url, options) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (url === '/api/users/1/avatar' && options?.method === 'POST') {
        return Promise.resolve({ ok: true, text: async () => '' });
      }
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(true));
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());

    const goodFile = new File(['x'], 'me.png', { type: 'image/png' });
    const input = document.getElementById('avatar-input-1');
    fireEvent.change(input, { target: { files: [goodFile] } });

    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('usersManagement.messages.avatarUpdated'));
  });

  test('avatar upload: a server refusal warns with the reason', async () => {
    global.fetch = jest.fn((url, options) => {
      if (url === '/api/users') return Promise.resolve(jsonResponse(USERS));
      if (url === '/api/users/1/avatar' && options?.method === 'POST') {
        return Promise.resolve({ ok: false, text: async () => 'too large for the server' });
      }
      if (String(url).includes('/avatar')) return Promise.resolve(blobResponse(false));
      throw new Error(`unexpected fetch ${url}`);
    });
    jest.spyOn(console, 'error').mockImplementation(() => {});

    render(<UsersManagement />);
    await waitFor(() => expect(screen.getByText('Ada Lovelace')).toBeInTheDocument());

    const goodFile = new File(['x'], 'me.png', { type: 'image/png' });
    const input = document.getElementById('avatar-input-1');
    fireEvent.change(input, { target: { files: [goodFile] } });

    await waitFor(() =>
      expect(toast.warning).toHaveBeenCalledWith('usersManagement.messages.avatarError too large for the server')
    );
  });
});
