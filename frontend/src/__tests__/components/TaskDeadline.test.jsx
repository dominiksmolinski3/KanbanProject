import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskDeadline from '../../components/taskDetails/TaskDeadline';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }),
}));

describe('TaskDeadline', () => {
  it('seeds the editor with the stored instant in this browser\'s local time', () => {
    const stored = new Date(2030, 9, 20, 10, 0).toISOString();
    render(<TaskDeadline deadline={stored} readOnly={false} onSave={jest.fn()} />);

    fireEvent.click(screen.getByLabelText('taskActions.editDeadline'));

    expect(screen.getByLabelText('taskActions.deadline')).toHaveValue('2030-10-20T10:00');
  });

  it('saves the local time it was given as the instant that time names, offset and all', async () => {
    const onSave = jest.fn(() => Promise.resolve(true));
    render(<TaskDeadline deadline={null} readOnly={false} onSave={onSave} />);

    fireEvent.click(screen.getByLabelText('taskActions.editDeadline'));
    fireEvent.change(screen.getByLabelText('taskActions.deadline'), { target: { value: '2030-10-21T09:30' } });
    fireEvent.click(screen.getByText('taskActions.save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ deadline: new Date(2030, 9, 21, 9, 30).toISOString() }));
    expect(onSave.mock.calls[0][0].deadline).toMatch(/Z$/);
  });

  it('clearing the field clears the deadline', async () => {
    const onSave = jest.fn(() => Promise.resolve(true));
    render(<TaskDeadline deadline={new Date(2030, 0, 1, 8, 0).toISOString()} readOnly={false} onSave={onSave} />);

    fireEvent.click(screen.getByLabelText('taskActions.editDeadline'));
    fireEvent.change(screen.getByLabelText('taskActions.deadline'), { target: { value: '' } });
    fireEvent.click(screen.getByText('taskActions.save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ deadline: null }));
  });
});
