package pl.myproject.kanbanproject2.board.event;

/**
 * The message a board's subscribers receive: what changed, and on which board. Nothing else.
 *
 * <p><b>It carries no entity, deliberately, and the reason is the transport rather than taste.</b>
 * A STOMP topic has no per-subscriber filtering - every subscriber of a destination receives every
 * frame published to it - so whatever is in this record is readable by anybody who is subscribed.
 * Keeping it to a kind and an id means the subscription check in
 * {@code BoardSubscriptionInterceptor} is the only thing that has to be right; a payload carrying
 * task titles would need to be right as well, in a second place, forever.
 *
 * <p>The second reason is drift. A DTO serialised here would be a second copy of the read model,
 * able to disagree with what {@code GET /api/tasks} returns for the same task. A kind says
 * "re-read", and the re-read goes through the route that already decides what this caller may see.
 *
 * <p>It also carries no actor. The obvious use for one is to let a client ignore the change it
 * made itself, but an account is not a client: the same account open in two tabs would have the
 * second tab discard the event it needed. Coalescing on the client is what makes the duplicate
 * read cheap instead, and it is right in both cases.
 */
public record BoardEvent(BoardEventType type, Integer boardId) {
}
