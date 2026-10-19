import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor, act } from '@testing-library/react';
import { toast } from 'react-toastify';
import { useAttachments } from '../../components/taskDetails/attachments/useAttachments';
import * as api from '../../services/api';
import { createQueryClient } from '../../services/queryClient';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${values.name}` : key) })
}));
jest.mock('react-toastify', () => ({ toast: { error: jest.fn(), success: jest.fn() } }));
jest.mock('../../services/api', () => ({
  ...jest.requireActual('../../services/api'),
  fetchTaskAttachments: jest.fn(),
  uploadTaskAttachment: jest.fn(),
  deleteTaskAttachment: jest.fn(),
  downloadTaskAttachment: jest.fn()
}));

const files = () => [
  { id: 1, fileName: 'a.txt', size: 1 },
  { id: 2, fileName: 'b.txt', size: 2 }
];
const file = name => ({ name });

const setup = async () => {
  const client = createQueryClient();
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const hook = renderHook(() => useAttachments(7), { wrapper });
  await waitFor(() => expect(hook.result.current.loaded).toBe(true));
  return { client, ...hook };
};

beforeEach(() => {
  jest.resetAllMocks();
  console.error = jest.fn();
  api.fetchTaskAttachments.mockResolvedValue(files());
});

test.each([[null], [undefined], [{ error: 'x' }]])('an answer of %p reads as no attachments', async answer => {
  api.fetchTaskAttachments.mockResolvedValue(answer);

  const { result } = await setup();

  expect(result.current.attachments).toEqual([]);
});

test('several files upload in turn and the list is re-read once after all of them', async () => {
  api.uploadTaskAttachment.mockResolvedValue({});
  const { result } = await setup();

  await act(() => result.current.upload([file('x'), file('y'), file('z')]));

  expect(api.uploadTaskAttachment).toHaveBeenCalledTimes(3);
  expect(api.fetchTaskAttachments).toHaveBeenCalledTimes(2);
  expect(result.current.uploading).toBe(false);
});

test('one failing file is reported by name, the others still upload, and the list is re-read', async () => {
  api.uploadTaskAttachment.mockImplementation(async (id, f) => {
    if (f.name === 'y') {
      throw new Error('boom');
    }
  });
  const { result } = await setup();

  await act(() => result.current.upload([file('x'), file('y'), file('z')]));

  expect(api.uploadTaskAttachment).toHaveBeenCalledTimes(3);
  expect(toast.error).toHaveBeenCalledTimes(1);
  expect(toast.error).toHaveBeenCalledWith('taskActions.attachmentUploadFailed:y');
  expect(api.fetchTaskAttachments).toHaveBeenCalledTimes(2);
});

test('choosing no files does nothing', async () => {
  const { result } = await setup();

  await act(() => result.current.upload([]));
  await act(() => result.current.upload(null));

  expect(api.uploadTaskAttachment).not.toHaveBeenCalled();
  expect(api.fetchTaskAttachments).toHaveBeenCalledTimes(1);
});

test('removing drops only that attachment, confirms, and does not re-read', async () => {
  api.deleteTaskAttachment.mockResolvedValue({});
  const { result } = await setup();

  await act(() => result.current.remove(files()[0]));

  expect(api.deleteTaskAttachment).toHaveBeenCalledWith(7, 1);
  expect(result.current.attachments.map(a => a.id)).toEqual([2]);
  expect(toast.success).toHaveBeenCalledWith('taskActions.attachmentDeleted');
  expect(api.fetchTaskAttachments).toHaveBeenCalledTimes(1);
});

test('a failed removal keeps the list and toasts the reason', async () => {
  api.deleteTaskAttachment.mockRejectedValue(new Error('gone'));
  const { result } = await setup();

  await act(() => result.current.remove(files()[0]));

  expect(result.current.attachments).toHaveLength(2);
  expect(toast.success).not.toHaveBeenCalled();
  expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred:undefined');
});

test('a failed download names the file', async () => {
  api.downloadTaskAttachment.mockRejectedValue(new Error('no'));
  const { result } = await setup();

  await act(() => result.current.download(files()[1]));

  expect(api.downloadTaskAttachment).toHaveBeenCalledWith(7, 2, 'b.txt');
  expect(toast.error).toHaveBeenCalledWith('taskActions.attachmentDownloadFailed:b.txt');
});
