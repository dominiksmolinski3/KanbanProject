import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import FormTabs from '../../components/ui/FormTabs';

const tabs = [
  { id: 'column', label: 'Columns', testId: 'tab-column' },
  { id: 'row', label: 'Rows', disabled: true },
];

describe('FormTabs', () => {
  test('marks the active tab and only that one', () => {
    render(<FormTabs tabs={tabs} active="column" onChange={jest.fn()} />);

    expect(screen.getByText('Columns')).toHaveClass('active');
    expect(screen.getByText('Rows')).not.toHaveClass('active');
  });

  test('reports the id of the tab that was pressed', () => {
    const onChange = jest.fn();
    render(<FormTabs tabs={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }]} active="a" onChange={onChange} />);

    fireEvent.click(screen.getByText('B'));

    expect(onChange).toHaveBeenCalledWith('b');
  });

  test('a disabled tab cannot be chosen', () => {
    const onChange = jest.fn();
    render(<FormTabs tabs={tabs} active="column" onChange={onChange} />);

    expect(screen.getByText('Rows')).toBeDisabled();
    fireEvent.click(screen.getByText('Rows'));

    expect(onChange).not.toHaveBeenCalled();
  });

  test('carries the test id it was given', () => {
    render(<FormTabs tabs={tabs} active="column" onChange={jest.fn()} />);

    expect(screen.getByTestId('tab-column')).toHaveTextContent('Columns');
  });

  test('never submits a form it sits in', () => {
    const onSubmit = jest.fn((event) => event.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <FormTabs tabs={tabs} active="row" onChange={jest.fn()} />
      </form>
    );

    fireEvent.click(screen.getByText('Columns'));

    expect(onSubmit).not.toHaveBeenCalled();
  });
});
