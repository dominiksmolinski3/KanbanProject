import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardPage from '../../components/BoardPage';

jest.mock('../../components/Bench', () => () => <div>Bench</div>);
jest.mock('../../components/Board', () => () => <div>Board</div>);

describe('BoardPage', () => {
  test('renders the bench and the board together', () => {
    render(<BoardPage />);
    expect(screen.getByText('Bench')).toBeInTheDocument();
    expect(screen.getByText('Board')).toBeInTheDocument();
  });
});
