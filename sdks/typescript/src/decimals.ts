/**
 * Exact conversion between the integers the exchange counts in and decimal
 * text.
 *
 * Every price, unit and cash amount is a `number` holding an integer that
 * counts the smallest step of its scale: with `decimals` of 2, `5000` is
 * `"50.00"`. This is the one place the SDK multiplies or divides by a power of
 * ten, and it does so on strings and `BigInt`, never on floating point: a
 * float turns 0.29 at two decimals into 28.
 */

const GRAMMAR = /^-?[0-9]+(?:\.[0-9]+)?$/;

/**
 * Format and parse amounts at a given number of decimal places.
 *
 * {@link Decimals.parse} refuses rather than rounds. Text with a non-zero
 * digit beyond `decimals` is not a value the exchange can hold, and quietly
 * moving it to a neighbour is how two clients come to disagree about a price.
 * Trailing zeros are not refused: `"1.50"` at one decimal is exactly `15`.
 *
 * The grammar is the same in all three SDKs: an optional `-`, one or more
 * digits, and optionally a `.` followed by one or more digits. No `+`, no
 * exponent, no grouping, no surrounding space.
 *
 * The range is narrower than Java's and Python's: a `number` holds an integer
 * exactly only up to `Number.MAX_SAFE_INTEGER`, so anything beyond it is
 * refused rather than returned approximately.
 */
export class Decimals {
  /** Decimals of a price when the market does not say: cents. */
  static readonly DEFAULT_PRICE_DECIMALS = 2;

  /** Decimals of a unit count when the market does not say: whole units. */
  static readonly DEFAULT_UNIT_DECIMALS = 0;

  /** Decimals of cash when the marketplace does not say: cents. */
  static readonly DEFAULT_CASH_DECIMALS = 2;

  private constructor() {}

  /**
   * `value` as decimal text with exactly `decimals` digits after the point,
   * and no point when `decimals` is zero.
   *
   * `format(5000, 2)` is `"50.00"`; `format(-5, 2)` is `"-0.05"`. Throws a
   * `RangeError` if `value` is not a safe integer -- a fraction has already
   * lost the exactness this exists to keep -- or `decimals` is negative.
   */
  static format(value: number, decimals: number): string {
    requireDecimals(decimals);
    if (!Number.isSafeInteger(value)) {
      throw new RangeError(`value must be a safe integer, got ${value}`);
    }
    const sign = value < 0 ? "-" : "";
    let digits = BigInt(value < 0 ? -value : value).toString();
    if (decimals === 0) return sign + digits;
    digits = digits.padStart(decimals + 1, "0");
    return `${sign}${digits.slice(0, -decimals)}.${digits.slice(-decimals)}`;
  }

  /**
   * Decimal text as the integer the exchange counts in, exactly.
   *
   * `parse("50", 2)` is `5000`. Throws a `RangeError` if `text` is not a
   * decimal number, has a non-zero digit beyond `decimals`, or is beyond
   * `Number.MAX_SAFE_INTEGER` either way, or if `decimals` is negative.
   */
  static parse(text: string, decimals: number): number {
    requireDecimals(decimals);
    if (typeof text !== "string" || !GRAMMAR.test(text)) {
      throw new RangeError(`not a decimal number: ${JSON.stringify(text)}`);
    }
    const negative = text.startsWith("-");
    const [whole, fraction = ""] = (negative ? text.slice(1) : text).split(".");
    if (/[1-9]/.test(fraction.slice(decimals))) {
      throw new RangeError(
        `${JSON.stringify(text)} has more than ${decimals} decimal places`,
      );
    }
    const magnitude = BigInt(whole + fraction.slice(0, decimals).padEnd(decimals, "0"));
    if (magnitude > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new RangeError(`${JSON.stringify(text)} is out of range`);
    }
    const value = Number(magnitude);
    return negative && value !== 0 ? -value : value;
  }
}

function requireDecimals(decimals: number): void {
  if (!Number.isInteger(decimals) || decimals < 0) {
    throw new RangeError(`decimals must be a non-negative integer, got ${decimals}`);
  }
}
