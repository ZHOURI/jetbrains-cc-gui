/**
 * Detection for the "press ← twice in an empty composer" gesture that opens
 * the Agent View panel.
 */

/** Maximum gap between the two presses, measured keydown to keydown. */
export const DOUBLE_ARROW_LEFT_WINDOW_MS = 500;

export interface ArrowLeftEventInfo {
  key: string;
  metaKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
  /** Holding the key repeats keydown; a repeat never starts or completes a gesture. */
  repeat?: boolean;
  /** IME candidate confirmation must not be read as a gesture press. */
  isComposing?: boolean;
}

function isEligibleArrowLeft(event: ArrowLeftEventInfo, inputEmpty: boolean): boolean {
  return (
    event.key === 'ArrowLeft' &&
    !event.metaKey &&
    !event.ctrlKey &&
    !event.altKey &&
    !event.shiftKey &&
    !event.repeat &&
    !event.isComposing &&
    inputEmpty
  );
}

/**
 * Whether this keydown completes the double-← gesture started at
 * {@code previousAt}. A missing, backwards, or stale mark never matches.
 */
export function isDoubleArrowLeft(
  previousAt: number | null,
  event: ArrowLeftEventInfo,
  now: number,
  inputEmpty: boolean,
  windowMs = DOUBLE_ARROW_LEFT_WINDOW_MS,
): boolean {
  if (!isEligibleArrowLeft(event, inputEmpty) || previousAt === null) return false;
  const delta = now - previousAt;
  return delta >= 0 && delta <= windowMs;
}

/**
 * The mark to carry into the next keydown: the current time for an eligible
 * press, otherwise null (any other key or a non-empty draft resets the
 * gesture).
 */
export function nextArrowLeftMark(
  event: ArrowLeftEventInfo,
  now: number,
  inputEmpty: boolean,
): number | null {
  return isEligibleArrowLeft(event, inputEmpty) ? now : null;
}
