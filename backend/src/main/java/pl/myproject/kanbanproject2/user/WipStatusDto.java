package pl.myproject.kanbanproject2.user;

/**
 * What {@code GET /users/{id}/wip-status} answers.
 *
 * <p>The route used to return a bare {@code true}/{@code false}, which told the caller nothing it
 * could show a person: not whose limit it was, not what the limit is, not how close to it they
 * already are. A client cannot render "Anna is at 5 of 5" from a boolean, and the frontend's read
 * of a field that never existed on it went unnoticed for exactly that reason.
 *
 * @param userId        the account the answer is about
 * @param wipLimit      the configured limit, or {@code null} when the user has none
 * @param assignedCount how many tasks the user is assigned to right now
 * @param withinLimit   whether one more assignment would be accepted
 */
public record WipStatusDto(Integer userId, Integer wipLimit, int assignedCount, boolean withinLimit) {
}
