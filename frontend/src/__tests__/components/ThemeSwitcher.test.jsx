import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import ThemeSwitcher from '../../components/ThemeSwitcher';
import { THEME_CHANGE_EVENT, THEME_STORAGE_KEY } from '../../theme/themePreference';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key })
}));

describe('ThemeSwitcher', () => {
  beforeEach(() => window.localStorage.clear());

  test('marks the system option when nothing is stored', () => {
    render(<ThemeSwitcher />);

    expect(screen.getByRole('group', { name: 'header.theme' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'header.themeSystem' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'header.themeDark' })).toHaveAttribute('aria-pressed', 'false');
  });

  test('reads a stored choice', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'light');
    render(<ThemeSwitcher />);

    expect(screen.getByRole('button', { name: 'header.themeLight' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('choosing one stores it and tells the page to re-apply', () => {
    const onChange = jest.fn();
    window.addEventListener(THEME_CHANGE_EVENT, onChange);
    render(<ThemeSwitcher />);

    fireEvent.click(screen.getByRole('button', { name: 'header.themeDark' }));

    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'header.themeDark' })).toHaveAttribute('aria-pressed', 'true');
    window.removeEventListener(THEME_CHANGE_EVENT, onChange);
  });
});
