import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import Account from '../../components/Account';
import { clearAvatarCache } from '../../board/useUserAvatar';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values && values.email ? `${key}:${values.email}` : key) }),
}));
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 7, email: 'ada@example.test', name: 'Ada Lovelace' },
    login: jest.fn(),
    logout: jest.fn(() => Promise.resolve()),
  }),
}));
jest.mock('../../services/authService', () => ({
  authService: { requestEmailChange: jest.fn(), confirmEmailChange: jest.fn(), changePassword: jest.fn() },
}));
jest.mock('../../services/api', () => ({
  getUserAvatar: jest.fn(),
  uploadUserAvatar: jest.fn(),
}));
jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));

const { authService } = require('../../services/authService');
const { getUserAvatar, uploadUserAvatar } = require('../../services/api');
const { toast } = require('react-toastify');

URL.revokeObjectURL = jest.fn();

const renderAccount = () => render(<MemoryRouter><Account /></MemoryRouter>);

const fileInput = () => document.querySelector('input[type="file"]');

const choose = (file) => fireEvent.change(fileInput(), { target: { files: [file] } });

const picture = (type = 'image/png', size = 1024) => {
  const file = new File(['x'], 'me.png', { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
};

const failure = (code, message = code) => Object.assign(new Error(message), { code });

describe('Account profile', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    clearAvatarCache();
    getUserAvatar.mockResolvedValue(null);
    uploadUserAvatar.mockResolvedValue(undefined);
  });

  test('shows the initials when there is no picture', async () => {
    renderAccount();

    expect(await screen.findByText('AL')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  test('shows the stored picture when there is one', async () => {
    getUserAvatar.mockResolvedValue('blob:stored');
    const { container } = renderAccount();

    await waitFor(() => expect(container.querySelector('img.account-avatar')).toHaveAttribute('src', 'blob:stored'));
  });

  test('refuses a file that is not a picture, without asking the server', async () => {
    renderAccount();

    await act(async () => { choose(picture('application/pdf')); });

    expect(toast.info).toHaveBeenCalledWith('usersManagement.messages.fileTypeError');
    expect(uploadUserAvatar).not.toHaveBeenCalled();
  });

  test('refuses a picture over a megabyte, without asking the server', async () => {
    renderAccount();

    await act(async () => { choose(picture('image/png', 1024 * 1024 + 1)); });

    expect(toast.info).toHaveBeenCalledWith('usersManagement.messages.fileTooLarge');
    expect(uploadUserAvatar).not.toHaveBeenCalled();
  });

  test('uploads a good picture, drops the cached one and shows the new one', async () => {
    const file = picture();
    renderAccount();
    await screen.findByText('AL');
    getUserAvatar.mockResolvedValue('blob:fresh');

    await act(async () => { choose(file); });

    expect(uploadUserAvatar).toHaveBeenCalledWith(7, file);
    expect(toast.success).toHaveBeenCalledWith('usersManagement.messages.avatarUpdated');
    await waitFor(() => expect(document.querySelector('img.account-avatar')).toHaveAttribute('src', 'blob:fresh'));
  });

  test('is disabled while the picture is being sent, and free again afterwards', async () => {
    let finish;
    uploadUserAvatar.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    renderAccount();
    await screen.findByText('AL');

    act(() => { choose(picture()); });
    await waitFor(() => expect(fileInput()).toBeDisabled());

    await act(async () => { finish(); });
    await waitFor(() => expect(fileInput()).not.toBeDisabled());
  });

  test('a refused upload shows the server\'s words, or a general line when it gave none', async () => {
    renderAccount();
    await screen.findByText('AL');

    uploadUserAvatar.mockRejectedValueOnce(new Error('Too sharp'));
    await act(async () => { choose(picture()); });
    expect(toast.error).toHaveBeenLastCalledWith('Too sharp');

    uploadUserAvatar.mockRejectedValueOnce(new Error(''));
    await act(async () => { choose(picture()); });
    expect(toast.error).toHaveBeenLastCalledWith('account.errors.generic');
    expect(fileInput()).not.toBeDisabled();
  });
});

describe('Account forms', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    clearAvatarCache();
    getUserAvatar.mockResolvedValue(null);
  });

  test('the eye button shows the password and hides it again', () => {
    renderAccount();
    const field = document.getElementById('account-current-password');
    const reveal = field.parentElement.querySelector('.account-reveal');

    expect(field).toHaveAttribute('type', 'password');
    fireEvent.click(reveal);
    expect(field).toHaveAttribute('type', 'text');
    expect(reveal).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(reveal);
    expect(field).toHaveAttribute('type', 'password');
  });

  test.each([
    ['WRONG_PASSWORD', 'account.errors.wrongPassword'],
    ['EMAIL_UNCHANGED', 'account.errors.unchanged'],
    ['INVALID_VERIFICATION_CODE', 'account.errors.invalidCode'],
    ['VERIFICATION_CODE_EXPIRED', 'account.errors.expiredCode'],
    ['VALIDATION_ERROR', 'account.errors.invalid'],
    ['SOMETHING_NEW', 'account.errors.generic'],
    [undefined, 'account.errors.generic'],
  ])('a refused password change with code %s reads as %s', async (code, key) => {
    authService.changePassword.mockRejectedValue(failure(code));
    renderAccount();

    fireEvent.change(document.getElementById('account-current-password'), { target: { value: 'old-password' } });
    fireEvent.change(document.getElementById('account-new-password'), { target: { value: 'new-password-1' } });
    fireEvent.click(screen.getByText('account.password.submit'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(key));
  });

  test('a code step can be abandoned, which brings the address form back', async () => {
    authService.requestEmailChange.mockResolvedValue(undefined);
    renderAccount();

    fireEvent.change(screen.getByLabelText('account.email.newLabel'), { target: { value: 'new@example.test' } });
    fireEvent.change(document.getElementById('account-email-password'), { target: { value: 'secret-pass' } });
    fireEvent.click(screen.getByText('account.email.send'));
    expect(await screen.findByLabelText('account.email.codeLabel')).toBeInTheDocument();

    fireEvent.click(screen.getByText('account.email.cancel'));

    expect(screen.getByLabelText('account.email.newLabel')).toBeInTheDocument();
    expect(screen.queryByLabelText('account.email.codeLabel')).not.toBeInTheDocument();
  });

  test('the code field takes digits only', async () => {
    authService.requestEmailChange.mockResolvedValue(undefined);
    renderAccount();
    fireEvent.change(screen.getByLabelText('account.email.newLabel'), { target: { value: 'new@example.test' } });
    fireEvent.change(document.getElementById('account-email-password'), { target: { value: 'secret-pass' } });
    fireEvent.click(screen.getByText('account.email.send'));

    fireEvent.change(await screen.findByLabelText('account.email.codeLabel'), { target: { value: '12ab34' } });

    expect(screen.getByLabelText('account.email.codeLabel')).toHaveValue('1234');
  });
});
