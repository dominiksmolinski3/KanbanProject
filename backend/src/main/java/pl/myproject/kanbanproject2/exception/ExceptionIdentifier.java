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
    FILE_UPLOAD_FAILED(INTERNAL_SERVER_ERROR, "A server error occurred while processing the file"),
    FILE_NOT_FOUND(NOT_FOUND, "File not found"),

    /*
     * Task attachments. The bytes live in Azure Blob Storage and the row that names them lives
     * here, so there are two ways for one to go wrong and they are not the same kind of mistake.
     *
     * ATTACHMENT_NOT_FOUND is 404 for the reason BOARD_NOT_FOUND is: an attachment on somebody
     * else's task must answer exactly as one that was never uploaded, or the ids - which are small
     * and sequential - map out every board in the deployment.
     *
     * ATTACHMENT_STORAGE_UNAVAILABLE is 503 and is the deployment's fault rather than the caller's.
     * It means no storage account is configured, which is the state a fresh clone and CI run in;
     * saying so plainly is better than a 500, because the file was fine and trying again will not
     * help until somebody sets the properties.
     */
    ATTACHMENT_NOT_FOUND(NOT_FOUND, "Attachment not found"),
    ATTACHMENT_TOO_LARGE(PAYLOAD_TOO_LARGE, "The maximum attachment size is 10 MB"),
    INVALID_ATTACHMENT(BAD_REQUEST, "The uploaded file cannot be attached"),
    ATTACHMENT_STORAGE_UNAVAILABLE(SERVICE_UNAVAILABLE,
            "File storage is not configured, so attachments cannot be stored"),

    /*
     * The unauthenticated routes answer three statuses between them and no more: 202 for signup
     * and resend whatever the address turns out to be, 401 for every login failure including an
     * unverified account, and 400 for a verification code that is wrong, expired, or attached to
     * an address with no account. USER_ALREADY_EXISTS, ACCOUNT_NOT_VERIFIED and
     * ACCOUNT_ALREADY_VERIFIED are gone rather than left unused, because each of them is exactly
     * the answer that turns one of those routes back into a membership oracle, and an unused
     * constant is an invitation to reach for it.
     */
    INVALID_CREDENTIALS(UNAUTHORIZED, "Invalid email or password"),
    /*
     * A session the caller asked to end and cannot: no such row, somebody else's row, or one that
     * is already withdrawn or expired. One answer for the three, and 404 rather than 403, for the
     * reason BOARD_NOT_FOUND spells out below - ids here are sequential, so a status that told a
     * caller their neighbour's id was real would let anyone count everybody's live sessions.
     */
    SESSION_NOT_FOUND(NOT_FOUND, "Session not found"),
    VERIFICATION_CODE_EXPIRED(BAD_REQUEST, "The verification code has expired"),
    INVALID_VERIFICATION_CODE(BAD_REQUEST, "Invalid verification code"),
    /*
     * One answer for four facts. An unknown address, an account with no reset in flight, and a
     * wrong code are all INVALID_RESET_CODE, because the other three describe the account rather
     * than the request and would tell an unauthenticated caller which addresses have accounts.
     * Expiry is separate only because reaching it already required a valid code.
     */
    /*
     * One answer for four different failures: no token, a token the provider rejects, a token it
     * has already seen, and a check that could not be completed at all. Which of those happened is
     * the provider's business and not the caller's - and the only useful action is the same in
     * every case, which is to solve a fresh challenge.
     */
    CAPTCHA_FAILED(BAD_REQUEST, "Captcha verification failed, please try again"),
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
     * A reorder that cannot mean anything: an empty or duplicated id list, or - for tasks - ids
     * drawn from more than one cell. A position is an ordinal within one container, so numbering
     * across two of them produces two tasks at position 0 and no way to say which comes first.
     * The caller's mistake, so 400, with the specific message passed at each throw site.
     */
    INVALID_REORDER(BAD_REQUEST, "The requested order cannot be applied"),

    /*
     * A board, or anything on one, that the caller is not a member of answers BOARD_NOT_FOUND and
     * not a 403. The distinction is the whole point: 403 confirms the object exists, which lets a
     * caller map out somebody else's board by walking ids and reading the status codes. The same
     * reasoning already applies to TASK_NOT_FOUND, COLUMN_NOT_FOUND and ROW_NOT_FOUND, which the
     * services now raise for an object on a board the caller cannot see.
     *
     * NOT_BOARD_OWNER is a 403 precisely because it is only ever reached by a caller who can
     * already see the board, so it discloses nothing new.
     */
    BOARD_NOT_FOUND(NOT_FOUND, "Board not found"),
    NOT_BOARD_OWNER(FORBIDDEN, "Only the board owner can do that"),
    CANNOT_REMOVE_BOARD_OWNER(BAD_REQUEST, "The board owner cannot be removed from the board"),
    BOARD_MISMATCH(BAD_REQUEST, "That object belongs to a different board"),

    /*
     * A language the mail templates have no bundle for. The caller's mistake, so 400 - and a
     * refusal rather than a fallback, because this is only ever reached by somebody setting the
     * language explicitly. Signup, which guesses from a browser header, falls back to English
     * without complaining; a guess that misses costs nothing, and quietly storing English over
     * a choice does.
     */
    UNSUPPORTED_LOCALE(BAD_REQUEST, "That language is not one this application can write mail in"),

    /*
     * Someone else changed the same task, column, row or subtask between the caller reading it and
     * saving it back. A 409 rather than a 500: nothing is broken, the caller just has a stale copy
     * and the fix is to reload and reapply. Raised from Hibernate's optimistic lock, not thrown by
     * a service.
     */
    CONCURRENT_MODIFICATION(CONFLICT, "This item was changed by someone else - reload and try again");

    private final HttpStatus status;
    private final String defaultMessage;

    ExceptionIdentifier(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
