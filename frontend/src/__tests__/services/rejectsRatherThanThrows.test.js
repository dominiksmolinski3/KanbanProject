import { addColumn } from '../../services/columnApi';
import { addRow } from '../../services/rowApi';
import { addTask, fetchDailyFocusTasks, fetchTasks } from '../../services/taskApi';
import { getAllLabels } from '../../services/taskSearchApi';

global.fetch = jest.fn();

afterEach(() => {
  jest.restoreAllMocks();
});

test.each([
  ['fetchTasks', () => fetchTasks()],
  ['addTask', () => addTask('a task', 1)],
  ['fetchDailyFocusTasks', () => fetchDailyFocusTasks()],
  ['getAllLabels', () => getAllLabels()],
  ['addColumn', () => addColumn('a column', 3)],
  ['addRow', () => addRow('a lane', 3)],
])('%s answers a blocked localStorage with a rejection, not a throw', async (_, call) => {
  jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('storage is blocked');
  });

  let returned;
  expect(() => { returned = call(); }).not.toThrow();
  await expect(returned).rejects.toThrow('storage is blocked');
});
