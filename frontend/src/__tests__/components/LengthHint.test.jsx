import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import LengthHint from '../../components/LengthHint';
import EditableText from '../../components/EditableText';
import AddTaskForm from '../../components/AddTaskForm';
import { NAME_MAX_LENGTH } from '../../services/textLimits';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key)
  })
}));

jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => ({
    columns: [{ id: 1, name: 'To Do' }],
    rows: [],
    addTask: jest.fn(),
  })
}));

describe('LengthHint', () => {
  test('says nothing while the value is well short of the limit', () => {
    const { container } = render(<LengthHint value={'x'.repeat(89)} max={100} />);

    expect(container).toBeEmptyDOMElement();
  });

  test('counts down from ninety percent of the limit', () => {
    render(<LengthHint value={'x'.repeat(90)} max={100} />);

    expect(screen.getByRole('status')).toHaveTextContent('forms.textLimit.remaining:{"count":10}');
    expect(screen.getByRole('status')).not.toHaveClass('at-limit');
  });

  test('warns once the limit is reached', () => {
    render(<LengthHint value={'x'.repeat(100)} max={100} />);

    expect(screen.getByRole('status')).toHaveTextContent('forms.textLimit.atLimit:{"max":100}');
    expect(screen.getByRole('status')).toHaveClass('at-limit');
  });
});

describe('the inline editor caps names at the server limit', () => {
  test('the input carries the limit and warns at it', () => {
    render(<EditableText id={1} text="Column" onUpdate={jest.fn()} />);
    fireEvent.doubleClick(screen.getByText('Column'));

    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('maxLength', String(NAME_MAX_LENGTH));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    fireEvent.change(input, { target: { value: 'x'.repeat(NAME_MAX_LENGTH) } });
    expect(screen.getByRole('status')).toHaveClass('at-limit');
  });
});

describe('the add task form caps the title', () => {
  test('the title input carries the limit and counts down near it', () => {
    render(<AddTaskForm onClose={jest.fn()} />);

    const title = screen.getByLabelText('forms.addTaskForm.titleLabel');
    expect(title).toHaveAttribute('maxLength', String(NAME_MAX_LENGTH));

    fireEvent.change(title, { target: { value: 'x'.repeat(NAME_MAX_LENGTH - 5) } });
    expect(screen.getByRole('status')).toHaveTextContent('forms.textLimit.remaining:{"count":5}');
  });
});
