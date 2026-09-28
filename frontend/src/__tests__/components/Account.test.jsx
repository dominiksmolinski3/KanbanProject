import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import Account from '../../components/Account';

const translation = { t: (key, values) => (values && values.email ? `${key}:${values.email}` : key) };
jest.mock('react-i18next', () => ({
  useTranslation: () => translation
}));

const mockLogin = jest.fn();
const mockLogout = jest.fn(() => Promise.resolve());
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 7, email: 'owner@example.test' },
    login: mockLogin,
    logout: mockLogout
  })
}));

jest.mock('../../services/authService', () => ({
  authService: {
    requestEmailChange: jest.fn(),
    confirmEmailChange: jest.fn(),
    changePassword: jest.fn()
  }
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn() }
}));

const { authService } = require('../../services/authService');
const { toast } = require('react-toastify');

const failure = (code) => Object.assign(new Error(code), { code });

describe('Account', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('shows the current address', () => {
    render(<Account />);
    expect(screen.getByText('account.email.current:owner@example.test')).toBeInTheDocument();
  });

  test('asks for a code with the new address and the password, then asks for the code', async () => {
    authService.requestEmailChange.mockResolvedValue();
    render(<Account />);

    fireEvent.change(screen.getByLabelText('account.email.newLabel'), { target: { value: 'new@example.test' } });
    fireEvent.change(screen.getAllByLabelText('account.password.currentLabel')[0], { target: { value: 'pw' } });
    fireEvent.click(screen.getByText('account.email.send'));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('account.email.sent:new@example.test'));
    expect(authService.requestEmailChange).toHaveBeenCalledWith(7, 'new@example.test', 'pw');
    expect(screen.getByLabelText('account.email.codeLabel')).toBeInTheDocument();
  });

  test('a confirmed code signs in with the session the server answers', async () => {
    authService.requestEmailChange.mockResolvedValue();
    const session = { token: 'jwt', refreshToken: 'r', expiresIn: 900000, sessionId: 3 };
    authService.confirmEmailChange.mockResolvedValue(session);
    render(<Account />);

    fireEvent.change(screen.getByLabelText('account.email.newLabel'), { target: { value: 'new@example.test' } });
    fireEvent.change(screen.getAllByLabelText('account.password.currentLabel')[0], { target: { value: 'pw' } });
    fireEvent.click(screen.getByText('account.email.send'));
    const codeField = await screen.findByLabelText('account.email.codeLabel');
    fireEvent.change(codeField, { target: { value: '12a3456' } });
    fireEvent.click(screen.getByText('account.email.confirm'));

    await waitFor(() => expect(mockLogin).toHaveBeenCalledWith(session));
    expect(authService.confirmEmailChange).toHaveBeenCalledWith(7, '123456');
    expect(toast.success).toHaveBeenCalledWith('account.email.changed');
  });

  test('a wrong password is shown as that, not as a generic failure', async () => {
    authService.requestEmailChange.mockRejectedValue(failure('WRONG_PASSWORD'));
    render(<Account />);

    fireEvent.change(screen.getByLabelText('account.email.newLabel'), { target: { value: 'new@example.test' } });
    fireEvent.change(screen.getAllByLabelText('account.password.currentLabel')[0], { target: { value: 'bad' } });
    fireEvent.click(screen.getByText('account.email.send'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('account.errors.wrongPassword'));
    expect(screen.queryByLabelText('account.email.codeLabel')).not.toBeInTheDocument();
  });

  test('a changed password signs out, because the server has ended every session', async () => {
    authService.changePassword.mockResolvedValue();
    render(<Account />);

    fireEvent.change(screen.getByLabelText('account.password.newLabel'), { target: { value: 'a-new-password' } });
    fireEvent.change(screen.getAllByLabelText('account.password.currentLabel')[1], { target: { value: 'old' } });
    fireEvent.click(screen.getByText('account.password.submit'));

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
    expect(authService.changePassword).toHaveBeenCalledWith(7, 'old', 'a-new-password');
    expect(toast.success).toHaveBeenCalledWith('account.password.changed');
  });
});
