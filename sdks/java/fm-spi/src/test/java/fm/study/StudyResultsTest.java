package fm.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What contract 0.6 says to the people in a run (fm-ui
 * docs/EDITOR-AND-STUDY-REDESIGN.md §7.4): lines that explain a payoff, a
 * reveal for the room, and a carry's event. Each defaults to nothing, which
 * leaves a study built before 0.6, and a run stored before it, as they were.
 */
class StudyResultsTest {

    @Test
    void aPayoffSaysHowItWasReached_orNothing_asBefore() {
        Payoff explained = new Payoff(7, "p@x", "P", 2400, List.of(
                new Payoff.Line("Cash at close", 1200L), new Payoff.Line("STK · 4 units × 3.00", 1200L),
                new Payoff.Line("You were a buyer", null)));
        Payoff plain = new Payoff(7, "p@x", "P", 2400);

        assertThat(explained.lines()).extracting(Payoff.Line::label)
                .containsExactly("Cash at close", "STK · 4 units × 3.00", "You were a buyer");
        assertThat(plain.lines()).isEmpty();
        assertThat(new Payoff(7, "p@x", "P", 2400, null).lines()).as("a payoff stored before 0.6").isEmpty();
        assertThatThrownBy(() -> new Payoff.Line(" ", 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPayoffsLinesAreItsOwnCopy() {
        List<Payoff.Line> lines = new ArrayList<>(List.of(new Payoff.Line("Cash at close", 100L)));
        Payoff payoff = new Payoff(1, "a@x", "A", 100, lines);
        lines.clear();

        assertThat(payoff.lines()).hasSize(1);
    }

    @Test
    void aSettlementRevealsWhatTheRoomMayNowBeTold_orNothing() {
        Settlement revealing = new Settlement(List.of(), List.of(), List.of(), List.of("Outcome A happened"));
        Settlement quiet = new Settlement(List.of(), List.of(), List.of());

        assertThat(revealing.reveal()).containsExactly("Outcome A happened");
        assertThat(quiet.reveal()).isEmpty();
        assertThat(new Settlement(null, null, null, null).reveal()).as("one stored before 0.6").isEmpty();
    }

    @Test
    void aCarryTellsTheRoomWhatItsPeriodDid_whicheverWayItWasMade() {
        Carry period = new Carry("h", null, "c").withEvent("Period 3 paid 0.28 a unit");
        Carry last = Carry.last("h", null, "c").withEvent("Period 4 paid nothing");
        Carry over = Carry.settle("c").withEvent("Liquidated: the claims outran the pool");

        assertThat(period).extracting(Carry::event, Carry::next, Carry::holdings)
                .containsExactly("Period 3 paid 0.28 a unit", Carry.Next.PERIOD, "h");
        assertThat(last.next()).isEqualTo(Carry.Next.LAST);
        assertThat(over).extracting(Carry::event, Carry::next, Carry::carried)
                .containsExactly("Liquidated: the claims outran the pool", Carry.Next.SETTLE, "c");
        assertThat(new Carry("h", null, "c").event()).isNull();
        assertThat(new Carry("h", null, "c").withEvent(" ").event()).as("blank says nothing").isNull();
    }
}
