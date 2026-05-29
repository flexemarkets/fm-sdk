/**
 * REST snapshot response bundle: parsed body + the `x-fm-as-of-seq`
 * header value the snapshot was read at. Returned by `Flexemarkets`
 * V1 snapshot methods so `MarketView` (and any other caller that
 * mixes REST snapshots with WS deltas) can reconcile the two
 * streams: apply deltas whose `seq` is greater than `asOfSeq` and
 * skip those whose seq is less than or equal.
 *
 * `asOfSeq` is `-1` when the server didn't stamp the response
 * (older fm-server). Callers that depend on the seq for correctness
 * should treat `-1` as "snapshot reconciliation unavailable" and
 * either wholesale-replace state on every snapshot fetch or fall
 * back to a periodic poll.
 */
export interface Snapshot<T> {
  readonly body: T;
  readonly asOfSeq: number;
}
