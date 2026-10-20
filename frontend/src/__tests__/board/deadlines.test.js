import { toInstant, toLocalInput } from '../../board/deadlines';

describe('deadlines', () => {
  test('a local date and time becomes the instant it names', () => {
    expect(toInstant('2030-10-21T09:30')).toBe(new Date(2030, 9, 21, 9, 30).toISOString());
  });

  test('an instant is shown back in local time, to the minute', () => {
    expect(toLocalInput(new Date(2030, 9, 21, 9, 30, 45).toISOString())).toBe('2030-10-21T09:30');
  });

  test('the two are inverses for any minute', () => {
    expect(toLocalInput(toInstant('2030-03-31T02:30') ?? '')).toBe(toLocalInput(new Date(2030, 2, 31, 2, 30).toISOString()));
    expect(toInstant(toLocalInput('2030-07-01T15:00:00Z'))).toBe('2030-07-01T15:00:00.000Z');
  });

  test('nothing, an empty field and nonsense are no deadline', () => {
    expect(toInstant('')).toBeNull();
    expect(toInstant(null)).toBeNull();
    expect(toInstant('not a date')).toBeNull();
    expect(toLocalInput(null)).toBe('');
    expect(toLocalInput('not a date')).toBe('');
  });
});
