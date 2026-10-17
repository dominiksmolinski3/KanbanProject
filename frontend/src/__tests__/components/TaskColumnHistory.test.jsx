import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskColumnHistory from '../../components/taskDetails/TaskColumnHistory';
import * as api from '../../services/api';

let mockLanguage = 'en';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key} ${Object.values(values).join(' ')}` : key),
    i18n: { resolvedLanguage: mockLanguage }
  })
}));

jest.mock('../../services/api');

const HOUR = 60 * 60 * 1000;
const start = new Date('2026-09-01T10:00:00Z').getTime();

beforeEach(() => {
  mockLanguage = 'en';
  api.fetchColumns.mockResolvedValue([]);
  api.getTaskColumnHistory.mockResolvedValue([
    { id: 1, columnId: 1, columnName: 'To Do', changedAt: new Date(start).toISOString() },
    { id: 2, columnId: 2, columnName: 'Doing', changedAt: new Date(start + 26 * HOUR).toISOString() },
    { id: 3, columnId: 3, columnName: 'Done', changedAt: new Date(start + 26 * HOUR + 30 * 1000).toISOString() }
  ]);
  api.getTaskColumnTimeSpentSummary.mockResolvedValue([
    { columnId: 9, columnName: null, totalTimeMs: 3 * HOUR + 15 * 60 * 1000 }
  ]);
});

test('durations are formatted for the reader\'s language, not spelled out in English', async () => {
  render(<TaskColumnHistory taskId={7} />);

  expect(await screen.findByText('taskDetails.duration 1d 2h')).toBeInTheDocument();
  expect(screen.getByText('taskDetails.duration taskDetails.underAMinute')).toBeInTheDocument();
  expect(await screen.findByText('3h 15m')).toBeInTheDocument();
});

test('the same durations read in Polish units under a Polish locale', async () => {
  mockLanguage = 'pl';
  render(<TaskColumnHistory taskId={7} />);

  expect(await screen.findByText(/^taskDetails\.duration 1\sd\.\si\s2\sh$/)).toBeInTheDocument();
  expect(await screen.findByText(/^3\sh\si\s15\smin$/)).toBeInTheDocument();
});

test('a time-spent column with no recorded name is labelled as unknown', async () => {
  render(<TaskColumnHistory taskId={7} />);

  expect(await screen.findByText('taskDetails.unknownColumn')).toHaveClass('stat-column-name');
});
