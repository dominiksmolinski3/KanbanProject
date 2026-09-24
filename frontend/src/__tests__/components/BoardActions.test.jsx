import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardActions from '../../components/BoardActions';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

jest.mock('../../components/AddTaskForm', () => function MockAddTaskForm({ onClose }) {
  return <div data-testid="mock-add-task-form"><button onClick={onClose}>Close</button></div>;
});

jest.mock('../../components/WipLimitControl', () => function MockWipLimitControl({ onClose }) {
  return <div data-testid="mock-wip-limit-control"><button onClick={onClose}>Close</button></div>;
});

jest.mock('../../components/AddRowColumnForm', () => function MockAddRowColumnForm({ onClose }) {
  return <div data-testid="mock-add-board-item-form"><button onClick={onClose}>Close</button></div>;
});

describe('BoardActions', () => {
  test.each([
    ['open-add-task-form', 'mock-add-task-form'],
    ['open-wip-limit-form', 'mock-wip-limit-control'],
    ['open-add-board-item-form', 'mock-add-board-item-form'],
  ])('%s opens its form and the form closes itself', (button, form) => {
    render(<BoardActions />);
    fireEvent.click(screen.getByTestId(button));
    expect(screen.getByTestId(form)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Close'));
    expect(screen.queryByTestId(form)).not.toBeInTheDocument();
  });

  test('a second click on the same button closes its form', () => {
    render(<BoardActions />);
    fireEvent.click(screen.getByTestId('open-add-task-form'));
    fireEvent.click(screen.getByTestId('open-add-task-form'));
    expect(screen.queryByTestId('mock-add-task-form')).not.toBeInTheDocument();
  });

  test('only one form is open at a time', () => {
    render(<BoardActions />);
    fireEvent.click(screen.getByTestId('open-add-task-form'));
    fireEvent.click(screen.getByTestId('open-wip-limit-form'));
    expect(screen.queryByTestId('mock-add-task-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('mock-wip-limit-control')).toBeInTheDocument();
  });
});
