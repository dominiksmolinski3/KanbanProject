package pl.myproject.kanbanproject2.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

@Getter
public enum ExceptionIdentifier {

    USER_NOT_FOUND(NOT_FOUND, "User not found"),
    NOT_ACCOUNT_OWNER(FORBIDDEN, "You can only modify your own account"),

    AVATAR_NOT_FOUND(NOT_FOUND, "Avatar not found"),
    INVALID_AVATAR_FILE_TYPE(UNSUPPORTED_MEDIA_TYPE, "Only image files are allowed"),
    AVATAR_FILE_TOO_LARGE(PAYLOAD_TOO_LARGE, "The maximum file size is 1 MB"),
    /*
     * Shared with task attachments (FEAT-09 put avatars through the same BlobStore seam), which is
     * why the name says "file" rather than "avatar": the failure it describes - the store would not
     * take the bytes, or the upload could not be read - is the same failure either way.
     */
    FILE_UPLOAD_FAILED(INTERNAL_SERVER_ERROR, "A server error occurred while processing the file"),

    /*
     * ATTACHMENT_NOT_FOUND is 404 for the same oracle reason as BOARD_NOT_FOUND - ids are
     * sequential, so a wrong status would map every board's attachments.
     * ATTACHMENT_STORAGE_UNAVAILABLE is 503, the deployment's fault (no storage account configured,
     * the state CI and a fresh clone run in) rather than the caller's - and it is reused by avatar
     * uploads too, since both features read the same BlobStore.isConfigured(). ATTACHMENT_TRANSFER_BUSY
     * is reused the same way, by the avatar transfer semaphore.
     */
    ATTACHMENT_NOT_FOUND(NOT_FOUND, "Attachment not found"),
    ATTACHMENT_TOO_LARGE(PAYLOAD_TOO_LARGE, "The maximum attachment size is 10 MB"),
    INVALID_ATTACHMENT(BAD_REQUEST, "The uploaded file cannot be attached"),
    ATTACHMENT_STORAGE_UNAVAILABLE(SERVICE_UNAVAILABLE,
            "File storage is not configured, so attachments cannot be stored"),
    /*
     * Both transfer routes are held behind a bounded semaphore
     * (app.storage.max-concurrent-transfers) since a single-replica container can't absorb a burst;
     * tryAcquire fails fast so a caller gets an honest "try again" instead of a hanging connection.
     */
    ATTACHMENT_TRANSFER_BUSY(SERVICE_UNAVAILABLE,
            "Too many attachment transfers are in progress, please try again shortly"),
    /*
     * A board-wide ceiling (count and bytes, app.storage.max-attachments-per-board /
     * max-total-bytes-per-board) checked before the blob is written, so a rejection never orphans
     * one. 413 rather than 400: the request is fine, it's what the board already holds that makes
     * it too much.
     */
    ATTACHMENT_QUOTA_EXCEEDED(PAYLOAD_TOO_LARGE, "This board has reached its attachment quota"),
    /*
     * A Range naming bytes the attachment doesn't have. 416 rather than serving the whole file,
     * since handing back the start would let a resuming client write at the wrong offset. Answered
     * by the controller itself, not GlobalExceptionHandler, because a 416 needs a Content-Range
     * header the handler has no plumbing for.
     */
    ATTACHMENT_RANGE_NOT_SATISFIABLE(REQUESTED_RANGE_NOT_SATISFIABLE,
            "The requested byte range is outside this attachment"),

    /*
     * A negative page, an out-of-range size, or a deadline window ending before it starts. 400
     * rather than a quiet clamp - a caller silently capped at 100 rows can't tell that from a board
     * with exactly 100 matches, and will page past rows it never saw.
     */
    INVALID_SEARCH(BAD_REQUEST, "The search request cannot be served as asked"),

    /*
     * Same shape as INVALID_SEARCH, kept separate because a message naming "search" on the activity
     * feed would send somebody looking in the wrong place.
     */
    INVALID_ACTIVITY_REQUEST(BAD_REQUEST, "The activity feed cannot be paged as asked"),

    /*
     * The third of the same shape, for the chat scroll-back. Separate for the reason
     * INVALID_ACTIVITY_REQUEST is separate from INVALID_SEARCH: a message naming the wrong screen
     * sends somebody looking in the wrong place.
     */
    INVALID_CHAT_REQUEST(BAD_REQUEST, "The chat history cannot be paged as asked"),

    /*
     * The fourth of the same shape, for a card's comment thread (FEAT-06), separate for the same
     * reason the other three are.
     */
    INVALID_COMMENT_REQUEST(BAD_REQUEST, "The comment thread cannot be paged as asked"),

    /*
     * COMMENT_NOT_FOUND is 404 for the oracle reason ATTACHMENT_NOT_FOUND is - ids are sequential.
     * NOT_COMMENT_AUTHOR is 403 because it is only reached by a caller who can already read the
     * comment and is trying to rewrite or remove somebody else's.
     */
    COMMENT_NOT_FOUND(NOT_FOUND, "Comment not found"),
    NOT_COMMENT_AUTHOR(FORBIDDEN, "Only the author can change this comment"),

    /*
     * The flow metrics' window or column choice (FEAT-07): a window over the limit or running
     * backwards, a column not on the board, or a start column after the done column. Separate for
     * the reason the three above are.
     */
    INVALID_FLOW_REQUEST(BAD_REQUEST, "The flow metrics cannot be computed as asked"),

    /*
     * The unauthenticated routes answer only three statuses: 202 for signup/resend regardless of
     * the address, 401 for any login failure, 400 for a bad verification code. USER_ALREADY_EXISTS,
     * ACCOUNT_NOT_VERIFIED and ACCOUNT_ALREADY_VERIFIED are gone rather than unused, since each
     * would turn a route back into a membership oracle.
     */
    INVALID_CREDENTIALS(UNAUTHORIZED, "Invalid email or password"),
    /*
     * One 404 for a missing, someone else's, or already-withdrawn session - ids are sequential, so
     * a distinguishing status would let anyone count live sessions (same reasoning as
     * BOARD_NOT_FOUND).
     */
    SESSION_NOT_FOUND(NOT_FOUND, "Session not found"),
    VERIFICATION_CODE_EXPIRED(BAD_REQUEST, "The verification code has expired"),
    INVALID_VERIFICATION_CODE(BAD_REQUEST, "Invalid verification code"),
    /*
     * One answer for four failures - no token, a rejected token, a reused token, or an incomplete
     * check - since which one happened is the provider's business, not the caller's, and the fix is
     * the same either way: solve a fresh challenge.
     */
    CAPTCHA_FAILED(BAD_REQUEST, "Captcha verification failed, please try again"),
    /*
     * One answer for an unknown address, no reset in flight, or a wrong code, since the other three
     * describe the account rather than the request and would disclose which addresses have one.
     * Expiry is separate only because reaching it already required a valid code.
     */
    INVALID_RESET_CODE(BAD_REQUEST, "Invalid password reset code"),
    RESET_CODE_EXPIRED(BAD_REQUEST, "The password reset code has expired"),
    EMAIL_SEND_FAILED(INTERNAL_SERVER_ERROR, "Failed to send the email message"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later"),

    TASK_NOT_FOUND(NOT_FOUND, "Task not found"),
    SUBTASK_NOT_FOUND(NOT_FOUND, "Subtask not found"),
    PARENT_TASK_NOT_FOUND(NOT_FOUND, "Parent task not found"),
    PARENT_TASK_NOT_SET(NOT_FOUND, "The task does not have a parent task"),
    CYCLIC_TASK_DEPENDENCY(BAD_REQUEST, "The dependency would create a cycle, which is not allowed"),
    PARENT_TASK_NOT_COMPLETED(BAD_REQUEST, "A task cannot be completed before its parent tasks are completed"),
    USER_WIP_LIMIT_EXCEEDED(BAD_REQUEST, "The user's WIP limit has been exceeded"),

    COLUMN_NOT_FOUND(NOT_FOUND, "Column not found"),
    ROW_NOT_FOUND(NOT_FOUND, "Row not found"),

    /*
     * An empty or duplicated id list, or (for tasks) ids drawn from more than one cell - a position
     * is an ordinal within one container, so numbering across two produces two tasks at position 0.
     * The caller's mistake, so 400, with the specific message passed at each throw site.
     */
    INVALID_REORDER(BAD_REQUEST, "The requested order cannot be applied"),

    /*
     * A board, or anything on one, that the caller isn't a member of is BOARD_NOT_FOUND, never 403 -
     * a 403 would confirm the object exists, letting a caller map boards by walking ids.
     * TASK_NOT_FOUND, COLUMN_NOT_FOUND and ROW_NOT_FOUND follow the same rule. NOT_BOARD_OWNER is a
     * 403 because it's only reached by a caller who can already see the board.
     */
    BOARD_NOT_FOUND(NOT_FOUND, "Board not found"),
    NOT_BOARD_OWNER(FORBIDDEN, "Only the board owner can do that"),
    CANNOT_REMOVE_BOARD_OWNER(BAD_REQUEST, "The board owner cannot be removed from the board"),
    BOARD_MISMATCH(BAD_REQUEST, "That object belongs to a different board"),
    /*
     * A viewer trying to write - the 403 case the 404-not-403 rule reserves for a caller who can
     * already see the board, the same shape as NOT_BOARD_OWNER. Unlike that one this is reachable by
     * anyone on the board, not only a caller who tried an owner-only route.
     */
    VIEWER_READ_ONLY(FORBIDDEN, "This board is read-only for you"),

    /*
     * Any invitation not the caller's to act on (wrong id, another board's row, someone else's,
     * already answered) is one 404, same oracle reasoning as BOARD_NOT_FOUND. ALREADY_BOARD_MEMBER
     * is a 400 and discloses nothing, since only the board's owner - already looking at the member
     * list - can reach it.
     */
    INVITATION_NOT_FOUND(NOT_FOUND, "Invitation not found"),
    ALREADY_BOARD_MEMBER(BAD_REQUEST, "That person is already on this board"),

    /*
     * A language with no mail bundle. 400 and a refusal rather than a fallback, since this is only
     * reached when somebody sets the language explicitly - unlike signup's browser-header guess,
     * which falls back to English silently because a missed guess costs nothing but a stored wrong
     * choice does.
     */
    UNSUPPORTED_LOCALE(BAD_REQUEST, "That language is not one this application can write mail in"),

    /*
     * Someone else changed the same row between read and save. A 409, not 500 - nothing is broken,
     * reloading and reapplying fixes it. Raised from Hibernate's optimistic lock, not thrown by a
     * service.
     */
    CONCURRENT_MODIFICATION(CONFLICT, "This item was changed by someone else - reload and try again"),

    /*
     * The delivery-report webhook with no key configured or the wrong key: one 404 for both, the
     * same "you may not see this" rule as everywhere else here, since this is the only
     * unauthenticated write in the application. Event Grid doesn't read messages either way, and
     * whoever wired the subscription is looking at the key they pasted, not a status code.
     */
    MAIL_DELIVERY_REPORT_NOT_FOUND(NOT_FOUND, "Not found");

    private final HttpStatus status;
    private final String defaultMessage;

    ExceptionIdentifier(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
