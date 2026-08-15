package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link Types.Assets} puts on the wire, in both directions.
 *
 * <p>The server reads a participant's opening positions from {@code grants} and
 * writes them there too. The Java component is called {@code securities} so it
 * reads the same as {@code Holding.securities}, and the two are bridged by a
 * Jackson annotation — which makes the binding easy to get half right.
 *
 * <p>It <em>was</em> half right: {@code @JsonAlias("grants")} binds only when
 * parsing. Nothing in the SDK serialized this type, so nothing noticed. The
 * moment something does — posting an allocation — the request would carry
 * {@code securities}, the server would find no {@code grants}, and it would
 * create the allocation with the cash and no positions at all. A 200, and an
 * experiment whose participants hold nothing.
 *
 * <p>Hence a test of the encoding itself rather than of a call that uses it: the
 * property is about this type, holds whoever serializes it, and is worth
 * knowing independently of any one endpoint.
 */
class AssetsWireFormatTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static Types.Assets assets() {
        return new Types.Assets(null, "alice", 10_000,
                List.of(new Types.Security(10L, 50L, 50L, 0L, true, true)));
    }

    @Test
    void positionsAreWrittenAsGrants() {
        var json = mapper.writeValueAsString(assets());

        assertThat(json)
                .as("the server reads opening positions from 'grants'")
                .contains("\"grants\"")
                .doesNotContain("\"securities\"");
    }

    @Test
    void positionsAreReadFromGrants() {
        var parsed = mapper.readValue(
                "{\"cash\":10000,\"grants\":[{\"marketId\":10,\"units\":50}]}", Types.Assets.class);

        assertThat(parsed.cash()).isEqualTo(10_000L);
        assertThat(parsed.securities()).singleElement()
                .satisfies(s -> assertThat(s.marketId()).isEqualTo(10L));
    }

    /**
     * The alias is kept alongside the property so a response using the Java
     * spelling still parses. Dropping it in favour of {@code @JsonProperty}
     * alone would be a silent narrowing of what the SDK accepts.
     */
    @Test
    void positionsSpelledSecuritiesStillParse() {
        var parsed = mapper.readValue(
                "{\"cash\":10000,\"securities\":[{\"marketId\":10,\"units\":50}]}", Types.Assets.class);

        assertThat(parsed.securities()).singleElement()
                .satisfies(s -> assertThat(s.units()).isEqualTo(50L));
    }

    /**
     * A short allowance arrives under either name, depending on which response
     * produced the holding: fm-server's Asset emits "initialShortUnits" for a
     * live session, the allotments path emits "shortUnits".
     *
     * <p>Before 0.0.10 the field did not exist and the record ignores unknown
     * properties, so both spellings were dropped in silence -- a participant
     * permitted to short 50 read as one permitted to short nothing.
     */
    @Test
    void aShortAllowanceIsReadUnderEitherName() {
        var viaShortUnits = mapper.readValue(
                "{\"marketId\":10,\"units\":5,\"shortUnits\":50}", Types.Security.class);
        var viaInitial = mapper.readValue(
                "{\"marketId\":10,\"units\":5,\"initialShortUnits\":50}", Types.Security.class);

        assertThat(viaShortUnits.shortUnits()).isEqualTo(50L);
        assertThat(viaInitial.shortUnits()).isEqualTo(50L);
    }

    /** Absent means none, not null -- callers do arithmetic on this. */
    @Test
    void anAbsentShortAllowanceIsZero() {
        var parsed = mapper.readValue("{\"marketId\":10,\"units\":5}", Types.Security.class);

        assertThat(parsed.shortUnits()).isZero();
    }

    /** Requests carry "shortUnits", which is the name /allocations reads. */
    @Test
    void aShortAllowanceIsWrittenAsShortUnits() {
        var json = mapper.writeValueAsString(new Types.Security(10L, 5L, 55L, 50L, true, true));

        assertThat(json).contains("\"shortUnits\":50");
    }

    /** Same shape one level up: responses spell the nested capital either way. */
    @Test
    void allotmentAcceptsCapitalOrAssets() {
        var viaCapital = mapper.readValue(
                "{\"ownerId\":8,\"capital\":{\"cash\":10000}}", Types.Allotment.class);
        var viaAssets = mapper.readValue(
                "{\"ownerId\":8,\"assets\":{\"cash\":10000}}", Types.Allotment.class);

        assertThat(viaCapital.assets().cash()).isEqualTo(10_000L);
        assertThat(viaAssets.assets().cash()).isEqualTo(10_000L);
    }
}
