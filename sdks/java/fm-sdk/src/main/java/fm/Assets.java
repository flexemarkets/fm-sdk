package fm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The capital half of an allotment: opening cash and opening positions.
 *
 * <p>The positions cross the wire as {@code grants} in BOTH directions --
 * that is the server's own {@code Assets.grants} -- while the component
 * stays {@code securities} so it reads the same as
 * {@link Holding#securities()} on this side.
 *
 * <p>Hence {@code @JsonProperty}, which binds both ways, and not
 * {@code @JsonAlias} alone, which binds only on the way in. That was the
 * original defect: nothing serialized this type, so being half-bound cost
 * nothing until {@code allocate()} posted one -- at which point the request
 * would carry "securities", the server would find no "grants", and the
 * allocation would be created with the cash and no positions. Accepted,
 * 200, and quietly wrong. The alias is kept so a response spelling it
 * "securities" still parses.
 *
 * @param id         the assets' own id
 * @param name       the holder's display name
 * @param cash       opening cash, in the cents the exchange counts in
 * @param securities the opening position in each market
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Assets(
    Long id,
    String name,
    long cash,
    @JsonProperty("grants") @JsonAlias("securities") List<Security> securities) {
}
