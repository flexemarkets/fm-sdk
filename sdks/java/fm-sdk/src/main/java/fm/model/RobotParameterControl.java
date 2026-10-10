package fm.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The range a manager exposes to participants for one robot parameter.
 *
 * <p>Both ends are inclusive. {@code step} is how the participant's control
 * moves; the server refuses a launch value outside the range, not one off the
 * step.
 *
 * @param min  the lowest value a participant may choose
 * @param max  the highest value a participant may choose
 * @param step the control's increment
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotParameterControl(BigDecimal min, BigDecimal max, BigDecimal step) {
}
