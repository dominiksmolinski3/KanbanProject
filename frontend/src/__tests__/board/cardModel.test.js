import { deadlineState, initialsOf, priorityOfLabel, readStoredLabelColors, splitPriority } from '../../board/cardModel';

describe('priority is read off the labels', () => {
  test.each([
    ['High Priority', 'high'],
    ['priority: low', 'low'],
    ['URGENT', 'urgent'],
    ['P2', 'medium'],
    ['critical', 'urgent'],
    ['Bug', null],
    ['Highlight', null],
    ['Lower bound', null],
  ])('%p reads as %p', (label, expected) => {
    expect(priorityOfLabel(label)).toBe(expected);
  });

  test('the most severe priority wins and the others stay ordinary labels', () => {
    expect(splitPriority(['Low Priority', 'Bug', 'Urgent'])).toEqual({
      priority: 'urgent',
      priorityLabel: 'Urgent',
      labels: ['Bug', 'Low Priority'],
    });
    expect(splitPriority([])).toEqual({ priority: null, priorityLabel: null, labels: [] });
  });
});

describe('deadlineState', () => {
  const now = new Date('2026-09-25T12:00:00');

  test.each([
    [null, null],
    ['not a date', null],
    ['2026-09-25T11:59:00', 'overdue'],
    ['2026-09-26T12:00:00', 'soon'],
    ['2026-09-28T12:00:00', 'later'],
  ])('%p is %p', (deadline, expected) => {
    expect(deadlineState(deadline, now)).toBe(expected);
  });
});

test('initials come from the first and last name part', () => {
  expect(initialsOf('Ada Lovelace')).toBe('AL');
  expect(initialsOf('grace')).toBe('G');
  expect(initialsOf('')).toBe('?');
  expect(initialsOf(undefined)).toBe('?');
});

test('a corrupt label colour map is ignored rather than thrown', () => {
  localStorage.setItem('labelColors', '{not json');
  expect(readStoredLabelColors()).toEqual({});
  localStorage.setItem('labelColors', JSON.stringify({ Bug: '#f00' }));
  expect(readStoredLabelColors()).toEqual({ Bug: '#f00' });
  localStorage.removeItem('labelColors');
});
