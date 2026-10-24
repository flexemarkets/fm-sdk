package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fm.manifest.ParameterSpec;
import fm.study.SessionOrder.Side;
import fm.study.SessionOrder.State;

/**
 * Contract 0.2 hands a study the session's orders and lets a carry end the
 * run. Both are optional: a 0.1 study, paid from holdings, answers the host's
 * new calls through its own, and its carries run the plan to its end.
 */
class StudyProviderCloseTest {

    private static final Rotation ROTATION = new Rotation(1, "Period 1", "mp", "holdings.csv", null);
    private static final ParticipantHolding HOLDING = new ParticipantHolding(7, "a@uni.edu", "A", 1000, 900, null);
    private static final SessionOrder CLAIM = new SessionOrder(11, 7, "a@uni.edu", "CLM", Side.SELL, 125, 2,
            State.RESTING, null);

    /** A 0.1 study: it carries and settles from holdings alone. */
    private static final class Periodic implements StudyProvider {
        List<ParticipantHolding> settled;
        List<ParticipantHolding> carriedFrom;

        @Override public String id() { return "periodic"; }
        @Override public String name() { return "Periodic"; }
        @Override public String description() { return ""; }
        @Override public List<ParameterSpec> parameters() { return List.of(); }
        @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Settlement settle(Map<String, String> parameters, Rotation rotation,
                List<ParticipantHolding> holdings, String carried) {
            settled = holdings;
            return new Settlement(List.of(), List.of("Carried"), List.of(List.of(carried)));
        }
        @Override public Carry carry(Map<String, String> parameters, Rotation rotation,
                List<ParticipantHolding> holdings, String carried) {
            carriedFrom = holdings;
            return new Carry("next.csv", null, carried + "+1");
        }
    }

    @Test
    void aStudyPaidFromHoldingsSettlesTheCloseFromItsHoldings() {
        Periodic study = new Periodic();
        Settlement settlement = study.settle(Map.of(), ROTATION, new Close(List.of(HOLDING), List.of(CLAIM)), "k");

        assertThat(study.settled).containsExactly(HOLDING);
        assertThat(settlement.rows()).containsExactly(List.of("k"));
    }

    @Test
    void aStudyPaidFromHoldingsCarriesTheCloseFromItsHoldings_andGoesOn() {
        Periodic study = new Periodic();
        Carry carry = study.carry(Map.of(), ROTATION, new Close(List.of(HOLDING), List.of(CLAIM)), "k");

        assertThat(study.carriedFrom).containsExactly(HOLDING);
        assertThat(carry.carried()).isEqualTo("k+1");
        assertThat(carry.next()).isEqualTo(Carry.Next.PERIOD);
    }

    @Test
    void aStudyThatDoesNotCarryStillSaysSoThroughTheClose() {
        StudyProvider planned = new StudyProvider() {
            @Override public String id() { return "planned"; }
            @Override public String name() { return "Planned"; }
            @Override public String description() { return ""; }
            @Override public List<ParameterSpec> parameters() { return List.of(); }
            @Override public StudyPlan setup(Map<String, String> parameters, List<Participant> participants) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> planned.carry(Map.of(), ROTATION, Close.of(List.of(HOLDING)), null))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("planned");
    }

    @Test
    void aCloseKeepsItsOwnCopies_andHasNoNulls() {
        List<SessionOrder> orders = new ArrayList<>(List.of(CLAIM));
        Close close = new Close(List.of(HOLDING), orders);
        orders.clear();

        assertThat(close.orders()).containsExactly(CLAIM);
        assertThat(new Close(null, null).holdings()).isEmpty();
        assertThat(Close.of(List.of(HOLDING)).orders()).isEmpty();
    }

    @Test
    void anOrderTradedWithSomeoneOnlyWhenItTraded() {
        assertThat(CLAIM.resting()).isTrue();
        assertThat(new SessionOrder(12, 7, "a@uni.edu", "N05", Side.SELL, 300, 1, State.TRADED, 13L).resting())
                .isFalse();
        assertThatThrownBy(() -> new SessionOrder(12, 7, "a@uni.edu", "N05", Side.SELL, 300, 1, State.TRADED, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("counterparty");
        assertThatThrownBy(() -> new SessionOrder(12, 7, "a@uni.edu", "N05", Side.SELL, 300, 1, State.CANCELLED, 13L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("counterparty");
    }

    @Test
    void anOrderIsForSomethingOneWayInSomeState() {
        assertThatThrownBy(() -> new SessionOrder(1, 7, "a@uni.edu", " ", Side.BUY, 1, 1, State.RESTING, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbol");
        assertThatThrownBy(() -> new SessionOrder(1, 7, "a@uni.edu", "S", null, 1, 1, State.RESTING, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("side");
        assertThatThrownBy(() -> new SessionOrder(1, 7, "a@uni.edu", "S", Side.BUY, 1, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("state");
        assertThatThrownBy(() -> new SessionOrder(1, 7, "a@uni.edu", "S", Side.BUY, 1, 0, State.RESTING, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("units");
    }

    @Test
    void aCarryCanEndTheRun() {
        Carry last = Carry.last("next.csv", "state.csv", "liquidated=4");
        assertThat(last.next()).isEqualTo(Carry.Next.LAST);
        assertThat(last.holdings()).isEqualTo("next.csv");

        Carry settle = Carry.settle("liquidated=4");
        assertThat(settle.next()).isEqualTo(Carry.Next.SETTLE);
        assertThat(settle.holdings()).isNull();
        assertThat(settle.carried()).isEqualTo("liquidated=4");

        assertThat(new Carry("next.csv", null, null, null).next()).isEqualTo(Carry.Next.PERIOD);
    }

    @Test
    void aCarryThatSettlesStagesNothing_andOneThatGoesOnStagesSomething() {
        assertThatThrownBy(() -> new Carry("next.csv", null, null, Carry.Next.SETTLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stages nothing");
        assertThatThrownBy(() -> new Carry(null, "state.csv", null, Carry.Next.SETTLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stages nothing");
        assertThatThrownBy(() -> Carry.last(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holdings");
    }
}
