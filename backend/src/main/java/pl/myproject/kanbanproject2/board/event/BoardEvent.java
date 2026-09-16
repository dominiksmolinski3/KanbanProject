package pl.myproject.kanbanproject2.board.event;

/**
 * The message a board's subscribers receive: what changed, and on which board. Nothing else.
 *
 * <p>No entity, because a STOMP topic has no per-subscriber filtering — every subscriber gets every
 * frame — so keeping it to a kind and an id means only {@code BoardSubscriptionInterceptor}'s
 * subscription check has to be right, rather than a payload as well; a kind says "re-read", and the
 * re-read goes through the route that already decides what this caller may see rather than shipping
 * a second, driftable copy of the read model.
 *
 * <p>No actor either: an account is not a client, so filtering out "my own" change would make a
 * second open tab discard the event it needed. Coalescing the duplicate read on the client is cheap
 * and right in both cases.
 */
public record BoardEvent(BoardEventType type, Integer boardId) {
}
