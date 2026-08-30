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
     * The unauthenticated routes answer three statuses between them and no more: 202 for signup
     * and resend whatever the address turns out to be, 401 for every login failure including an
     * unverified account, and 400 for a verification code that is wrong, expired, or attached to
     * an address with no account. USER_ALREADY_EXISTS, ACCOUNT_NOT_VERIFIED and
     * ACCOUNT_ALREADY_VERIFIED are gone rather than left unused, because each of them is exactly
     * the answer that turns one of those routes back into a membership oracle, and an unused
     * constant is an invitation to reach for it.
     */
    INVALID_CREDENTIALS(UNAUTHORIZED, "Invalid email or password"),
    VERIFICATION_CODE_EXPIRED(BAD_REQUEST, "The verification code has expired"),
    INVALID_VERIFICATION_CODE(BAD_REQUEST, "Invalid verification code"),
    /*
     * One answer for four facts. An unknown address, an account with no reset in flight, and a
     * wrong code are all INVALID_RESET_CODE, because the other three describe the account rather
     * than the request and would tell an unauthenticated caller which addresses have accounts.
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
    ROW_NOT_FOUND(NOT_FOUND, "Row not found");

    private final HttpStatus status;
    private final String defaultMessage;

    ExceptionIdentifier(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
