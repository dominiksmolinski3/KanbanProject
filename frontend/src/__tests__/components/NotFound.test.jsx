import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@testing-library/jest-dom';
import NotFound from '../../components/NotFound';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('NotFound', () => {
  test('renders the translated title, message and a link home', () => {
    render(<MemoryRouter><NotFound /></MemoryRouter>);

    expect(screen.getByText('notFound.title')).toBeInTheDocument();
    expect(screen.getByText('notFound.message')).toBeInTheDocument();
    const link = screen.getByText('notFound.backHome');
    expect(link).toHaveAttribute('href', '/');
  });
});
