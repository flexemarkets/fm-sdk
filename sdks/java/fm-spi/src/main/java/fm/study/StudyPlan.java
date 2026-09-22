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
    /**
     * A plan is checked as it is made: distinct marketplace keys, rotations
     * numbered from 1 in order, and every rotation in a marketplace the
     * plan has. A host stages these one at a time, hours apart, so a plan
     * that does not hold together is caught here rather than then.
     */
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

    /**
     * The rotations that run in one marketplace, in order.
     *
     * @param marketplaceKey a {@link MarketplaceSpec#key}
     * @return its rotations, empty when the key is not the plan's
     */
    public List<Rotation> rotationsIn(String marketplaceKey) {
        return rotations.stream().filter(r -> r.marketplaceKey().equals(marketplaceKey)).toList();
    }
}
