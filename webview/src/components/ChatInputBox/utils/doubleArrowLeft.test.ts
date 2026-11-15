import {
  DOUBLE_ARROW_LEFT_WINDOW_MS,
  isDoubleArrowLeft,
  nextArrowLeftMark,
} from './doubleArrowLeft.js';

const left = (overrides: Partial<Parameters<typeof isDoubleArrowLeft>[1]> = {}) => ({
  key: 'ArrowLeft',
  ...overrides,
});

describe('isDoubleArrowLeft', () => {
  const now = 10_000;

  it('matches two presses inside the window on an empty draft', () => {
    expect(isDoubleArrowLeft(now - 200, left(), now, true)).toBe(true);
    expect(isDoubleArrowLeft(now - DOUBLE_ARROW_LEFT_WINDOW_MS, left(), now, true)).toBe(true);
  });

  it('does not match without a previous press', () => {
    expect(isDoubleArrowLeft(null, left(), now, true)).toBe(false);
  });

  it('does not match when the gap is too long', () => {
    expect(isDoubleArrowLeft(now - DOUBLE_ARROW_LEFT_WINDOW_MS - 1, left(), now, true)).toBe(false);
  });

  it('does not match when the draft is not empty', () => {
    expect(isDoubleArrowLeft(now - 100, left(), now, false)).toBe(false);
  });

  it('does not match with modifiers', () => {
    for (const modifier of ['metaKey', 'ctrlKey', 'altKey', 'shiftKey'] as const) {
      expect(isDoubleArrowLeft(now - 100, left({ [modifier]: true }), now, true)).toBe(false);
    }
  });

  it('does not match auto-repeat or IME presses', () => {
    expect(isDoubleArrowLeft(now - 100, left({ repeat: true }), now, true)).toBe(false);
    expect(isDoubleArrowLeft(now - 100, left({ isComposing: true }), now, true)).toBe(false);
  });

  it('does not match other keys or backwards time', () => {
    expect(isDoubleArrowLeft(now - 100, left({ key: 'ArrowRight' }), now, true)).toBe(false);
    expect(isDoubleArrowLeft(now + 100, left(), now, true)).toBe(false);
  });
});

describe('nextArrowLeftMark', () => {
  it('records an eligible press and resets on everything else', () => {
    expect(nextArrowLeftMark(left(), 42, true)).toBe(42);
    expect(nextArrowLeftMark(left(), 42, false)).toBeNull();
    expect(nextArrowLeftMark(left({ shiftKey: true }), 42, true)).toBeNull();
    expect(nextArrowLeftMark(left({ repeat: true }), 42, true)).toBeNull();
    expect(nextArrowLeftMark({ key: 'a' }, 42, true)).toBeNull();
  });
});
