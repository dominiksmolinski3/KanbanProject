import { WIDTH, niceMax, tooltipAt } from '../../components/flow/chartGeometry';

describe('niceMax', () => {
  test.each([
    [0, 1],
    [1, 1],
    [3, 3],
    [4, 4],
  ])('a small value (%s) is used as it is, but never below one', (value, expected) => {
    expect(niceMax(value)).toBe(expected);
  });

  test.each([
    [4.5, 6],
    [7, 8],
    [9, 10],
    [23, 30],
    [130, 200],
    [480, 600],
    [1100, 2000],
  ])('%s rounds up to %s, a top the three gridlines can sit on', (value, expected) => {
    expect(niceMax(value)).toBe(expected);
  });

  test('is never below the value it was given', () => {
    for (let value = 0.5; value < 5000; value *= 1.37) {
      expect(niceMax(value)).toBeGreaterThanOrEqual(value);
    }
  });
});

describe('tooltipAt', () => {
  test('opens to the right of a point in the left half', () => {
    expect(tooltipAt(WIDTH * 0.2)).toEqual({ left: '20%', transform: 'translateX(12px)' });
  });

  test('opens to the left of a point in the right half, so it stays inside the chart', () => {
    expect(tooltipAt(WIDTH * 0.8)).toEqual({ left: '80%', transform: 'translateX(calc(-100% - 12px))' });
  });
});
