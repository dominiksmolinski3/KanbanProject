import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import WipLimitControl from '../../components/WipLimitControl';
import KanbanContext from '../../context/KanbanContext';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('WipLimitControl with the numeric ids the API sends', () => {
  const updateWipLimit = jest.fn().mockResolvedValue({});
  const updateRowWipLimit = jest.fn().mockResolvedValue({});
  const value = {
    columns: [{ id: 7, name: 'To Do', wipLimit: 3 }, { id: 8, name: 'Done', wipLimit: 0 }],
    rows: [{ id: 21, name: 'Features', wipLimit: 2 }],
    updateWipLimit,
    updateRowWipLimit,
  };

  const renderControl = () => render(
    <KanbanContext.Provider value={value}>
      <WipLimitControl onClose={jest.fn()} />
    </KanbanContext.Provider>
  );

  beforeEach(() => jest.clearAllMocks());

  test('picking a column fills in its current limit', () => {
    renderControl();

    fireEvent.change(screen.getByLabelText('forms.wipLimit.selectColumn:'), { target: { value: '7' } });

    expect(screen.getByLabelText(/forms\.wipLimit\.limitLabel/i)).toHaveValue(3);
  });

  test('picking a row fills in its current limit', () => {
    renderControl();

    fireEvent.click(screen.getByText('forms.wipLimit.rowTab'));
    fireEvent.change(screen.getByLabelText('forms.wipLimit.selectRow:'), { target: { value: '21' } });

    expect(screen.getByLabelText(/forms\.wipLimit\.limitLabel/i)).toHaveValue(2);
  });

  test('saves against the numeric id, not the select\'s string', async () => {
    renderControl();

    fireEvent.change(screen.getByLabelText('forms.wipLimit.selectColumn:'), { target: { value: '8' } });
    fireEvent.change(screen.getByLabelText(/forms\.wipLimit\.limitLabel/i), { target: { value: '4' } });
    await act(async () => {
      fireEvent.click(screen.getByText('forms.wipLimit.submit'));
    });

    expect(updateWipLimit).toHaveBeenCalledWith(8, 4);
  });
});
