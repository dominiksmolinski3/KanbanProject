package pl.myproject.kanbanproject2.user;

/**
 * What {@code GET /users/{id}/wip-status} answers. The route used to return a bare
 * {@code true}/{@code false}, from which a client cannot render "Anna is at 5 of 5".
 *
 * @param userId        the account the answer is about
 * @param wipLimit      the configured limit, or {@code null} when the user has none
 * @param assignedCount how many tasks the user is assigned to right now
 * @param withinLimit   whether one more assignment would be accepted
 */
public record WipStatusDto(Integer userId, Integer wipLimit, int assignedCount, boolean withinLimit) {
}
