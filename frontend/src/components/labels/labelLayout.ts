export interface Position {
  top: number;
  left: number;
}

const PICKER_WIDTH = 250;
const PICKER_EDGE_GAP = 260;
const PICKER_BELOW_BUTTON = 5;
const FORM_HALF_WIDTH = 150;
const FORM_WIDTH = 300;
const FORM_EDGE_GAP = 310;
const FORM_MIN_TOP = 50;
const FORM_MIN_LEFT = 10;

export function pickerPositionFor(anchor: { left: number; bottom: number }, viewportWidth: number): Position {
  let left = anchor.left;
  if (left + PICKER_WIDTH > viewportWidth) {
    left = viewportWidth - PICKER_EDGE_GAP;
  }
  return { top: anchor.bottom + PICKER_BELOW_BUTTON, left };
}

export function formPositionFor(viewportWidth: number, viewportHeight: number): Position {
  let left = viewportWidth / 2 - FORM_HALF_WIDTH;
  if (left + FORM_WIDTH > viewportWidth) {
    left = viewportWidth - FORM_EDGE_GAP;
  }
  return {
    top: Math.max(FORM_MIN_TOP, viewportHeight / 2 - FORM_HALF_WIDTH),
    left: Math.max(FORM_MIN_LEFT, left)
  };
}
