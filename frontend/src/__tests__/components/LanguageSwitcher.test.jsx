import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import LanguageSwitcher from '../../components/LanguageSwitcher';

const mockI18n = { language: 'en', changeLanguage: jest.fn() };
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ i18n: mockI18n })
}));

let mockUser = { id: 7 };
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: mockUser })
}));

jest.mock('../../services/api', () => ({
  updateUserLocale: jest.fn()
}));

const { updateUserLocale } = require('../../services/api');

const pick = (label) => {
  fireEvent.click(screen.getByRole('button', { name: /English/ }));
  fireEvent.click(screen.getByRole('button', { name: label }));
};

describe('LanguageSwitcher', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUser = { id: 7 };
    updateUserLocale.mockResolvedValue({});
  });

  it('changes the language on screen', () => {
    render(<LanguageSwitcher />);

    pick('日本語');

    expect(mockI18n.changeLanguage).toHaveBeenCalledWith('ja');
  });

  it('stores the choice on the account, so the mail follows the screen', async () => {
    render(<LanguageSwitcher />);

    pick('Polski');

    await waitFor(() => expect(updateUserLocale).toHaveBeenCalledWith(7, 'pl'));
  });

  it('does not call the API when nobody is signed in', () => {
    mockUser = null;
    render(<LanguageSwitcher />);

    pick('Deutsch');

    expect(mockI18n.changeLanguage).toHaveBeenCalledWith('de');
    expect(updateUserLocale).not.toHaveBeenCalled();
  });

  it('leaves the screen alone when the account cannot be updated', async () => {
    const complaint = jest.spyOn(console, 'error').mockImplementation(() => {});
    updateUserLocale.mockRejectedValue(new Error('nope'));
    render(<LanguageSwitcher />);

    pick('Français');

    await waitFor(() => expect(complaint).toHaveBeenCalled());
    expect(mockI18n.changeLanguage).toHaveBeenCalledWith('fr');
    complaint.mockRestore();
  });

  it('offers exactly the nine languages the mail templates can write', () => {
    render(<LanguageSwitcher />);
    fireEvent.click(screen.getByRole('button', { name: /English/ }));

    ['English', 'Polski', 'Deutsch', 'Español', 'Français', 'Italiano', '日本語', 'Русский', 'العربية']
      .forEach((label) => expect(screen.getByRole('button', { name: label })).toBeInTheDocument());
  });
});
