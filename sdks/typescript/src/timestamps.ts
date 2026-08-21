/**
 * Reading the two ways the server spells a moment.
 *
 * It sends both. Audit fields — `createdDate`, `lastModifiedDate`, a session's
 * `openDate` — come from a Java `LocalDateTime` and arrive bare:
 * `"2017-04-11T00:54:35.135"`, no zone, with three to nine fractional digits.
 * `expiresAt` comes from a real `Instant` and arrives `"2026-08-15T18:00:00Z"`.
 *
 * **A bare timestamp is UTC.** The server writes them from a clock running UTC.
 *
 * This matters more in JavaScript than anywhere else, because the language gets
 * it wrong by default. `new Date("2017-04-11T00:54:35.135")` is specified to
 * read a date-time with no zone as *local*, so in Denver it yields
 * `2017-04-11T06:54:35.135Z` — six hours adrift. It is right in UTC, right in
 * CI, and wrong on the laptop of anyone west of Greenwich, which is the worst
 * way for a bug to be distributed.
 *
 * So a bare value has its `Z` supplied before parsing, and the ambiguity stops
 * here rather than at each call site.
 */

/** Ends with `Z`, `+hh:mm` or `-hh:mm` after the time part. */
const ZONED = /(?:Z|[+-]\d{2}:?\d{2})$/;

/**
 * The moment a value names, or null if it names none.
 *
 * Null rather than a throw for an unreadable value, the same rule the enums
 * follow: one unparseable field should cost the caller that field, not the
 * whole response it arrived in. An `Invalid Date` is worse than either — it
 * spreads, comparing false against everything and rendering as "Invalid Date"
 * somewhere far from the parse.
 */
export function toInstant(value: string | null | undefined): Date | null {
  const trimmed = value?.trim();
  if (!trimmed) return null;

  const parsed = new Date(ZONED.test(trimmed) ? trimmed : `${trimmed}Z`);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}
