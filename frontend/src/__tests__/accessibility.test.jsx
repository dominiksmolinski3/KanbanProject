import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import BoardSwitcher from '../components/BoardSwitcher';
import ColumnHeader from '../components/board/ColumnHeader';
import RowHeader from '../components/board/RowHeader';
import EditableText from '../components/EditableText';
import SearchFacets from '../components/search/SearchFacets';
import CustomLabelForm from '../components/labels/CustomLabelForm';
import { useKanban } from '../context/KanbanContext';
import { axeViolations } from '../test-utils/axe';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, opts) => (opts ? `${key} ${JSON.stringify(opts)}` : key), i18n: { language: 'en' } }),
}));
jest.mock('../context/KanbanContext', () => ({
  useKanban: jest.fn(),
}));

const lane = { id: 7, name: 'Features', position: 0, wipLimit: 3, taskCount: 1, wipState: 'ok', isOverLimit: false, wipFill: 0.3 };

describe('controls a screen reader can name and reach', () => {
  beforeEach(() => {
    useKanban.mockReturnValue({
      boards: [{ id: 1, name: 'Mine', owned: true }, { id: 2, name: 'Theirs', owned: false }],
      activeBoard: { id: 1, name: 'Mine' },
      activeBoardId: 1,
      selectBoard: jest.fn(),
      createBoard: jest.fn(),
      myInvitations: [{ id: 3 }],
      dragAndDrop: { handleDragStart: jest.fn(), handleDragOver: jest.fn(), handleDrop: jest.fn() },
      readOnly: false,
      keyboardMove: { held: null },
      updateColumnName: jest.fn(),
      updateRowName: jest.fn(),
    });
  });

  it('the board switcher, open, with the create form showing', async () => {
    render(<BoardSwitcher />);
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    fireEvent.click(screen.getByText('+ boards.switcher.newBoard'));

    expect(await axeViolations()).toEqual([]);
  });

  it('a column and a row header, at rest and while renaming', async () => {
    render(
      <table>
        <thead><tr><ColumnHeader column={{ ...lane, id: 4, name: 'To Do' }} onDelete={jest.fn()} /></tr></thead>
        <tbody><tr><RowHeader row={lane} collapsed={false} onToggle={jest.fn()} onDelete={jest.fn()} /></tr></tbody>
      </table>
    );
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: /column.rename/ }));
    expect(screen.getByRole('textbox', { name: 'column.name' })).toHaveFocus();
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: /row.rename/ }));
    await act(async () => {});
    expect(screen.getByRole('textbox', { name: 'row.name' })).toHaveFocus();
    expect(screen.queryByRole('textbox', { name: 'column.name' })).not.toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it('a task title while it is being edited', async () => {
    render(<EditableText id={1} text="Write the docs" onUpdate={jest.fn()} label="taskActions.editTitle" />);
    fireEvent.doubleClick(screen.getByText('Write the docs'));

    expect(await axeViolations()).toEqual([]);
  });

  it('the search facets', async () => {
    const filters = {
      labels: [], assignees: [], completed: '', deadlineFrom: '', deadlineTo: '',
      toggleLabel: jest.fn(), toggleAssignee: jest.fn(), changeCompleted: jest.fn(),
      changeDeadlineFrom: jest.fn(), changeDeadlineTo: jest.fn(),
    };
    render(<SearchFacets filters={filters} labels={['bug']} users={[{ id: 1, name: 'Ann', email: 'ann@example.com' }]} />);

    expect(await axeViolations()).toEqual([]);
  });

  it('the custom label form', async () => {
    render(
      <CustomLabelForm position={{ top: 0, left: 0 }} name="" color="#888888"
        onNameChange={jest.fn()} onColorChange={jest.fn()} onAdd={jest.fn()} onCancel={jest.fn()} />
    );

    expect(await axeViolations()).toEqual([]);
  });
});
