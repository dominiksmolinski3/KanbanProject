import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import PageLoading from '../../components/PageLoading';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('PageLoading', () => {
  test('renders the translated loading message', () => {
    render(<PageLoading />);
    expect(screen.getByText('app.loading')).toBeInTheDocument();
  });
});
