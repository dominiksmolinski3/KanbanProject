import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskDeadline from '../../components/taskDetails/TaskDeadline';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }),
}));

describe('TaskDeadline', () => {
  it('seeds the editor with the stored wall-clock time, not its UTC reading', () => {
    const stored = '2030-10-20T10:00:00';
    render(<TaskDeadline deadline={stored} readOnly={false} onSave={jest.fn()} />);

    fireEvent.click(screen.getByLabelText('taskActions.editDeadline'));

    expect(screen.getByLabelText('taskActions.deadline')).toHaveValue('2030-10-20T10:00');
  });
});
