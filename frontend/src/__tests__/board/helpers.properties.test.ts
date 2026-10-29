import fc from 'fast-check';
import { wipFill, wipState } from '../../board/boardModel';
import { niceMax } from '../../components/flow/chartGeometry';
import { formPositionFor, pickerPositionFor } from '../../components/labels/labelLayout';

const count = fc.integer({ min: 0, max: 10_000 });
const limit = fc.integer({ min: 1, max: 10_000 });
const noLimit = fc.constantFrom(null, undefined, 0, -1);

describe('WIP helpers, for any count and limit', () => {
  test('a column is over its limit exactly when it holds more than the limit', () => {
    fc.assert(fc.property(count, limit, (n, max) => {
      expect(wipState(n, max) === 'over').toBe(n > max);
    }));
  });

  test('no limit means no state and an empty bar', () => {
    fc.assert(fc.property(count, noLimit, (n, max) => {
      expect(wipState(n, max)).toBe('none');
      expect(wipFill(n, max)).toBe(0);
    }));
  });

  test('the bar stays between empty and full, and never shrinks as tasks are added', () => {
    fc.assert(fc.property(count, count, limit, (a, b, max) => {
      const [fewer, more] = a <= b ? [a, b] : [b, a];
      expect(wipFill(fewer, max)).toBeGreaterThanOrEqual(0);
      expect(wipFill(more, max)).toBeLessThanOrEqual(100);
      expect(wipFill(fewer, max)).toBeLessThanOrEqual(wipFill(more, max));
    }));
  });
});

describe('the chart axis maximum, for any value', () => {
  test('covers the value without more than doubling it', () => {
    fc.assert(fc.property(fc.integer({ min: 5, max: 1_000_000_000 }), value => {
      const max = niceMax(value);
      expect(max).toBeGreaterThanOrEqual(value);
      expect(max).toBeLessThan(2 * value);
    }));
  });

  test('a small value still gets an axis of at least one', () => {
    fc.assert(fc.property(fc.integer({ min: 0, max: 4 }), value => {
      expect(niceMax(value)).toBe(Math.max(1, value));
    }));
  });
});

describe('label popup placement, for any viewport', () => {
  const viewport = fc.integer({ min: 320, max: 4_000 });

  test('the picker opens below its button and is pulled back inside a narrow viewport', () => {
    fc.assert(fc.property(viewport, fc.integer({ min: 0, max: 4_000 }), fc.integer({ min: 0, max: 4_000 }),
      (width, left, bottom) => {
        const position = pickerPositionFor({ left, bottom }, width);
        expect(position.top).toBeGreaterThan(bottom);
        if (left + 250 > width) expect(position.left + 250).toBeLessThanOrEqual(width);
      }));
  });

  test('the form never starts above or left of its margins', () => {
    fc.assert(fc.property(viewport, viewport, (width, height) => {
      const position = formPositionFor(width, height);
      expect(position.top).toBeGreaterThanOrEqual(50);
      expect(position.left).toBeGreaterThanOrEqual(10);
    }));
  });
});
