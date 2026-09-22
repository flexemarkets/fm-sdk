package fm.study;

/**
 * One person taking part, as the host knows them.
 *
 * @param id        the person's id on the platform
 * @param email     their email, which is what the study's files key on
 * @param firstName may be empty
 * @param lastName  may be empty
 */
public record Participant(long id, String email, String firstName, String lastName) {
    public Participant {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("a participant needs an email");
        }
        firstName = firstName == null ? "" : firstName;
        lastName = lastName == null ? "" : lastName;
    }
}
