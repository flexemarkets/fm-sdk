import { InvalidArgumentError } from "./client.js";

/**
 * One page of a collection that grows without bound, read with `?limit=` and
 * `?before=` (fm-server 4.17).
 *
 * A page is the `limit` newest rows by id, in the order the collection is
 * always answered in. `next` is what to pass as `before` for the page after
 * it -- the older rows -- and is `null` on the last page. The server says so
 * with `Link: <...?before=...>; rel="next"`; a page without that header is the
 * last, even when it is full.
 *
 * ```ts
 * let page = await fm.sessionsPage(marketplaceId, 100);
 * for (;;) {
 *   report(page.rows);
 *   if (!page.hasNext) break;
 *   page = await fm.sessionsPage(marketplaceId, 100, page.next!);
 * }
 * ```
 */
export class Page<T> {
  constructor(
    /** The page, in the collection's own order. */
    readonly rows: T[],
    /** The `before` that reads the older rows, or null when there are none. */
    readonly next: number | null,
  ) {}

  /** Whether older rows remain. */
  get hasNext(): boolean {
    return this.next !== null;
  }

  /**
   * A page of a collection already read whole, cut as fm-server cuts one: the
   * `limit` newest rows by id among those below `before`, kept in the
   * collection's order. For an implementation that reads only whole
   * collections -- a test fake -- to page the way the server does.
   *
   * The server also caps a page at 5,000 rows; this does not.
   */
  static of<T>(rows: readonly T[], id: (row: T) => number, limit: number, before?: number): Page<T> {
    if (limit < 1) throw new InvalidArgumentError(`limit is 1 or more, not ${limit}`);
    const older = rows.filter((row) => before === undefined || id(row) < before);
    const newest = older.map(id).sort((a, b) => b - a).slice(0, limit);
    if (newest.length === 0) return new Page<T>([], null);
    const oldest = newest[newest.length - 1];
    const page = older.filter((row) => id(row) >= oldest);
    return new Page(page, older.length > page.length ? oldest : null);
  }
}
