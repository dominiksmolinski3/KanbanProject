import { formatDuration, visiblePages } from '../../components/taskDetails/history/historyFormat';
import { formatFileSize } from '../../components/taskDetails/attachments/fileSize';

describe('visiblePages', () => {
  test('lists every page when there are few', () => {
    expect(visiblePages(3, 1)).toEqual([0, 1, 2]);
    expect(visiblePages(5, 4)).toEqual([0, 1, 2, 3, 4]);
  });

  test('keeps a window around the current page and marks what it hides', () => {
    expect(visiblePages(20, 10)).toEqual([0, 'start-ellipsis', 8, 9, 10, 11, 12, 'end-ellipsis', 19]);
  });

  test('anchors the window at the start without a leading ellipsis', () => {
    expect(visiblePages(20, 0)).toEqual([0, 1, 2, 3, 4, 'end-ellipsis', 19]);
  });

  test('anchors the window at the end without a trailing ellipsis', () => {
    expect(visiblePages(20, 19)).toEqual([0, 'start-ellipsis', 15, 16, 17, 18, 19]);
  });

  test('joins the first page to the window when only one page is hidden', () => {
    expect(visiblePages(20, 3)).toEqual([0, 1, 2, 3, 4, 5, 'end-ellipsis', 19]);
  });
});

describe('formatDuration', () => {
  const t = (key) => key;

  test('says under a minute below a minute', () => {
    expect(formatDuration(59_999, t, 'en')).toBe('taskDetails.underAMinute');
  });

  test('shows hours and minutes for less than a day', () => {
    expect(formatDuration((2 * 60 + 5) * 60_000, t, 'en')).toBe('2h 5m');
  });

  test('shows days and hours from a day up, and drops the minutes', () => {
    expect(formatDuration((26 * 60 + 40) * 60_000, t, 'en')).toBe('1d 2h');
  });

  test('leaves out a unit that is zero', () => {
    expect(formatDuration(3 * 60 * 60_000, t, 'en')).toBe('3h');
  });
});

describe('formatFileSize', () => {
  test('counts bytes below a kilobyte', () => {
    expect(formatFileSize(0)).toBe('0 B');
    expect(formatFileSize(1023)).toBe('1023 B');
  });

  test('uses one decimal below ten and whole numbers above', () => {
    expect(formatFileSize(1536)).toBe('1.5 KB');
    expect(formatFileSize(20 * 1024)).toBe('20 KB');
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  test('stops at gigabytes', () => {
    expect(formatFileSize(3 * 1024 ** 4)).toBe('3072 GB');
  });

  test('answers nothing for a size that makes no sense', () => {
    expect(formatFileSize(-1)).toBe('');
    expect(formatFileSize(NaN)).toBe('');
    expect(formatFileSize(Infinity)).toBe('');
  });
});
