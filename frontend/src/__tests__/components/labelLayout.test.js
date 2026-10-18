import { formPositionFor, pickerPositionFor } from '../../components/labels/labelLayout';

describe('pickerPositionFor', () => {
  test('sits just below the button, level with its left edge', () => {
    expect(pickerPositionFor({ left: 100, bottom: 40 }, { x: 0, y: 0 }, 1000)).toEqual({ top: 45, left: 100 });
  });

  test('follows the page scroll', () => {
    expect(pickerPositionFor({ left: 100, bottom: 40 }, { x: 30, y: 200 }, 1000)).toEqual({ top: 245, left: 130 });
  });

  test('moves left rather than run off the right edge of the viewport', () => {
    expect(pickerPositionFor({ left: 900, bottom: 40 }, { x: 0, y: 0 }, 1000)).toEqual({ top: 45, left: 740 });
  });

  test('a button whose picker just fits stays where it is', () => {
    expect(pickerPositionFor({ left: 750, bottom: 0 }, { x: 0, y: 0 }, 1000).left).toBe(750);
  });
});

describe('formPositionFor', () => {
  test('is centred in a large viewport', () => {
    expect(formPositionFor(1000, 800)).toEqual({ top: 250, left: 350 });
  });

  test('keeps clear of the top and left edges in a small one', () => {
    expect(formPositionFor(300, 200)).toEqual({ top: 50, left: 10 });
  });

  test('keeps clear of the left edge even in a viewport narrower than the form', () => {
    expect(formPositionFor(250, 600).left).toBe(10);
  });
});
