/**
 * A signed-up account nobody has decided about yet.
 *
 * fm-server declares `private Boolean approval` -- three states, not two. A
 * freshly signed-up account carries `"approval": null` until an administrator
 * approves or suspends it.
 *
 * TypeScript read that with `?? false`, which cannot tell null from absent from
 * false, and typed the field `boolean` besides. So a pending account arrived as
 * `approval: false`, which is what a *suspended* account looks like. Anything
 * gating on it treated "waiting for you" as "refused".
 *
 * Java has had this right since the field was boxed: `Boolean approval` plus
 * `isApproved()` folding null to false at the point of asking, rather than at
 * the point of parsing where the third state is lost for good.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { parseAccount } from "../src/client.ts";
import { isApproved } from "../src/types.ts";

test("a pending account is not a suspended one", () => {
  const account = parseAccount({ id: 5, name: "acme", approval: null });

  assert.equal(account.approval, null, "null became false, so pending reads as suspended");
});

test("an approved account is approved", () => {
  assert.equal(parseAccount({ id: 5, approval: true }).approval, true);
});

test("a suspended account is suspended", () => {
  assert.equal(parseAccount({ id: 5, approval: false }).approval, false);
});

test("a field the server omitted is also undecided", () => {
  // Absent and null mean the same thing here, and neither means false.
  assert.equal(parseAccount({ id: 5 }).approval, null);
});

test("isApproved answers the question callers actually ask", () => {
  // The convenience Java has, so a caller need not spell the null check.
  // Folding the third state away is fine here -- this is the point of asking,
  // not the point of parsing.
  assert.equal(isApproved(parseAccount({ id: 5, approval: true })), true);
  assert.equal(isApproved(parseAccount({ id: 5, approval: false })), false);
  assert.equal(isApproved(parseAccount({ id: 5, approval: null })), false);
});
