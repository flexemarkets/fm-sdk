package fm.study;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@link StudyProvider#setup} produces: the marketplaces to create,
 * the rotations to run in them, and the roster to hand out.
 *
 * @param marketplaces at least one; keys distinct
 * @param rotations    in running order; every rotation names a marketplace
 *                     in {@code marketplaces}
 * @param roster       the shareable half of the plan -- who is in what
 *                     group, what they do each rotation -- as CSV, or null
 *                     when the study has nothing to hand out. Never carries
 *                     a private valuation.
 */
public record StudyPlan(List<MarketplaceSpec> marketplaces, List<Rotation> rotations, String roster) {
    public StudyPlan {
        marketplaces = marketplaces == null ? List.of() : List.copyOf(marketplaces);
        rotations = rotations == null ? List.of() : List.copyOf(rotations);
        if (marketplaces.isEmpty()) {
            throw new IllegalArgumentException("a plan needs at least one marketplace");
        }
        Set<String> keys = new HashSet<>();
        for (MarketplaceSpec m : marketplaces) {
            if (!keys.add(m.key())) {
                throw new IllegalArgumentException("two marketplaces share the key '" + m.key() + "'");
            }
        }
        int expected = 1;
        for (Rotation r : rotations) {
            if (r.index() != expected++) {
                throw new IllegalArgumentException("rotations must be numbered 1.." + rotations.size() + " in order");
            }
            if (!keys.contains(r.marketplaceKey())) {
                throw new IllegalArgumentException("rotation " + r.index() + " names marketplace '"
                        + r.marketplaceKey() + "', which the plan does not have");
            }
        }
    }

    /** The rotations that run in one marketplace, in order. */
    public List<Rotation> rotationsIn(String marketplaceKey) {
        return rotations.stream().filter(r -> r.marketplaceKey().equals(marketplaceKey)).toList();
    }
}
