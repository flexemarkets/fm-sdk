package fm;

/**
 * REST snapshot response bundle: parsed body + the {@code x-fm-as-of-seq}
 * header value the snapshot was read at. Returned by {@code Flexemarkets}
 * V1 snapshot methods so {@link MarketView} (and any other caller that
 * mixes REST snapshots with WS deltas) can reconcile the two streams:
 * apply deltas whose seq is greater than {@link #asOfSeq()} and skip
 * those whose seq is less than or equal.
 *
 * <p>{@link #asOfSeq()} is {@code -1L} when the server didn't stamp the
 * response (older fm-server that doesn't speak V1 with snapshot
 * stamping yet). Callers that depend on the seq for correctness
 * should treat {@code -1L} as "snapshot reconciliation unavailable"
 * and either wholesale-replace state on every snapshot fetch or
 * fall back to a periodic poll.
 *
 * @param body    the parsed response body
 * @param asOfSeq the value of the {@code x-fm-as-of-seq} header, or
 *                {@code -1L} if absent
 */
public record Snapshot<T>(T body, long asOfSeq) {
    /** Sentinel for "header absent" — see class javadoc. */
    public static final long NO_SEQ = -1L;
}
