import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { useColumnHistory } from '../../components/taskDetails/history/useColumnHistory';
import * as api from '../../services/api';
import { createQueryClient } from '../../services/queryClient';

jest.mock('../../services/api');

const at = (seconds, millis = 0) => new Date(Date.UTC(2026, 8, 1, 10, 0, seconds, millis)).toISOString();

const setup = () => {
  const client = createQueryClient();
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return renderHook(() => useColumnHistory(7), { wrapper });
};

beforeEach(() => {
  jest.resetAllMocks();
  console.error = jest.fn();
  api.fetchColumns.mockResolvedValue([]);
  api.getTaskColumnTimeSpentSummary.mockResolvedValue([]);
});

test('an entry with no column name is named from the board columns, one that has a name keeps it', async () => {
  api.fetchColumns.mockResolvedValue([{ id: 1, name: 'To Do' }, { id: 2, name: 'Doing' }]);
  api.getTaskColumnHistory.mockResolvedValue([
    { id: 1, columnId: 1, changedAt: at(0) },
    { id: 2, columnId: 2, columnName: 'Own name', changedAt: at(60) }
  ]);

  const { result } = setup();

  await waitFor(() => expect(result.current.history).toHaveLength(2));
  expect(result.current.history.map(h => h.columnName)).toEqual(['To Do', 'Own name']);
});

test('a failing column lookup leaves the names empty but still returns the history', async () => {
  api.fetchColumns.mockRejectedValue(new Error('down'));
  api.getTaskColumnHistory.mockResolvedValue([{ id: 1, columnId: 1, changedAt: at(0) }]);

  const { result } = setup();

  await waitFor(() => expect(result.current.history).toHaveLength(1));
  expect(result.current.history[0].columnName).toBeUndefined();
});

test('the columns are not fetched when there is no history', async () => {
  api.getTaskColumnHistory.mockResolvedValue([]);

  const { result } = setup();
  await waitFor(() => expect(result.current.loadingTimeSpent).toBe(false));

  expect(api.fetchColumns).not.toHaveBeenCalled();
  expect(result.current.history).toEqual([]);
});

test('a null history reads as empty', async () => {
  api.getTaskColumnHistory.mockResolvedValue(null);

  const { result } = setup();
  await waitFor(() => expect(result.current.loadingTimeSpent).toBe(false));

  expect(result.current.history).toEqual([]);
});

test('entries for the same column within a second collapse into the first', async () => {
  api.getTaskColumnHistory.mockResolvedValue([
    { id: 1, columnId: 1, columnName: 'A', changedAt: at(0) },
    { id: 2, columnId: 1, columnName: 'A', changedAt: at(0, 500) },
    { id: 3, columnId: 2, columnName: 'B', changedAt: at(0) },
    { id: 4, columnId: 1, columnName: 'A', changedAt: at(2) }
  ]);

  const { result } = setup();

  await waitFor(() => expect(result.current.history.length).toBeGreaterThan(0));
  expect(result.current.history.map(h => h.id)).toEqual([1, 3, 4]);
});

test('time spent keeps loading until it is answered, then reports the rows', async () => {
  api.getTaskColumnHistory.mockResolvedValue([]);
  api.getTaskColumnTimeSpentSummary.mockResolvedValue([{ columnId: 1, columnName: 'A', totalTimeMs: 5 }]);

  const { result } = setup();
  expect(result.current.loadingTimeSpent).toBe(true);

  await waitFor(() => expect(result.current.loadingTimeSpent).toBe(false));
  expect(result.current.timeSpent).toHaveLength(1);
});

test('time spent stops loading when it fails and reads as empty', async () => {
  api.getTaskColumnHistory.mockResolvedValue([]);
  api.getTaskColumnTimeSpentSummary.mockRejectedValue(new Error('down'));

  const { result } = setup();

  await waitFor(() => expect(result.current.loadingTimeSpent).toBe(false));
  expect(result.current.timeSpent).toEqual([]);
});

test('a failing history leaves time spent unaffected', async () => {
  api.getTaskColumnHistory.mockRejectedValue(new Error('down'));
  api.getTaskColumnTimeSpentSummary.mockResolvedValue([{ columnId: 1, columnName: 'A', totalTimeMs: 5 }]);

  const { result } = setup();

  await waitFor(() => expect(result.current.timeSpent).toHaveLength(1));
  expect(result.current.history).toEqual([]);
});

test('a null time-spent answer reads as empty', async () => {
  api.getTaskColumnHistory.mockResolvedValue([]);
  api.getTaskColumnTimeSpentSummary.mockResolvedValue(null);

  const { result } = setup();

  await waitFor(() => expect(result.current.loadingTimeSpent).toBe(false));
  expect(result.current.timeSpent).toEqual([]);
});
