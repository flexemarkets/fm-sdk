/**
 * `Page.of` cuts a page as fm-server's `Paging.page` does.
 *
 * The client's pages are the route fixtures' to check; this is the cut a fake
 * that reads only whole collections pages with.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { InvalidArgumentError } from "../src/client.ts";
import { Page } from "../src/page.ts";

/** A collection answered newest first, so its order is not its id order. */
const NEWEST_FIRST = [50, 40, 30, 20, 10];
const ident = (row: number): number => row;

test("a page is the newest by id, in the collection's own order", () => {
  const page = Page.of(NEWEST_FIRST, ident, 2);

  assert.deepEqual(page.rows, [50, 40]);
  assert.equal(page.next, 40);
  assert.equal(page.hasNext, true);
});

test("before reads the older rows", () => {
  const page = Page.of(NEWEST_FIRST, ident, 2, 40);

  assert.deepEqual(page.rows, [30, 20]);
  assert.equal(page.next, 20);
});

test("a full last page has no next", () => {
  const page = Page.of(NEWEST_FIRST, ident, 2, 30);

  assert.deepEqual(page.rows, [20, 10]);
  assert.equal(page.next, null);
  assert.equal(page.hasNext, false);
});

test("a page with nothing older is empty and last", () => {
  const page = Page.of(NEWEST_FIRST, ident, 2, 10);

  assert.deepEqual(page.rows, []);
  assert.equal(page.next, null);
});

test("a limit below one is refused, as the server refuses it", () => {
  assert.throws(() => Page.of(NEWEST_FIRST, ident, 0), InvalidArgumentError);
});
