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

    ATTACHMENT_NOT_FOUND(NOT_FOUND, "Attachment not found"),
    ATTACHMENT_TOO_LARGE(PAYLOAD_TOO_LARGE, "The maximum attachment size is 10 MB"),
    INVALID_ATTACHMENT(BAD_REQUEST, "The uploaded file cannot be attached"),
    ATTACHMENT_STORAGE_UNAVAILABLE(SERVICE_UNAVAILABLE,
            "File storage is not configured, so attachments cannot be stored"),
    ATTACHMENT_TRANSFER_BUSY(SERVICE_UNAVAILABLE,
            "Too many attachment transfers are in progress, please try again shortly"),
    ATTACHMENT_QUOTA_EXCEEDED(PAYLOAD_TOO_LARGE, "This board has reached its attachment quota"),
    ATTACHMENT_RANGE_NOT_SATISFIABLE(REQUESTED_RANGE_NOT_SATISFIABLE,
            "The requested byte range is outside this attachment"),

    INVALID_SEARCH(BAD_REQUEST, "The search request cannot be served as asked"),

    INVALID_ACTIVITY_REQUEST(BAD_REQUEST, "The activity feed cannot be paged as asked"),

    INVALID_CHAT_REQUEST(BAD_REQUEST, "The chat history cannot be paged as asked"),

    INVALID_COMMENT_REQUEST(BAD_REQUEST, "The comment thread cannot be paged as asked"),

    COMMENT_NOT_FOUND(NOT_FOUND, "Comment not found"),
    NOT_COMMENT_AUTHOR(FORBIDDEN, "Only the author can change this comment"),

    INVALID_FLOW_REQUEST(BAD_REQUEST, "The flow metrics cannot be computed as asked"),

    INVALID_CREDENTIALS(UNAUTHORIZED, "Invalid email or password"),
    SESSION_NOT_FOUND(NOT_FOUND, "Session not found"),
    VERIFICATION_CODE_EXPIRED(BAD_REQUEST, "The verification code has expired"),
    INVALID_VERIFICATION_CODE(BAD_REQUEST, "Invalid verification code"),
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

    INVALID_REORDER(BAD_REQUEST, "The requested order cannot be applied"),

    BOARD_NOT_FOUND(NOT_FOUND, "Board not found"),
    NOT_BOARD_OWNER(FORBIDDEN, "Only the board owner can do that"),
    CANNOT_REMOVE_BOARD_OWNER(BAD_REQUEST, "The board owner cannot be removed from the board"),
    BOARD_MISMATCH(BAD_REQUEST, "That object belongs to a different board"),
    VIEWER_READ_ONLY(FORBIDDEN, "This board is read-only for you"),

    INVITATION_NOT_FOUND(NOT_FOUND, "Invitation not found"),
    ALREADY_BOARD_MEMBER(BAD_REQUEST, "That person is already on this board"),

    UNSUPPORTED_LOCALE(BAD_REQUEST, "That language is not one this application can write mail in"),

    CONCURRENT_MODIFICATION(CONFLICT, "This item was changed by someone else - reload and try again"),

    MAIL_DELIVERY_REPORT_NOT_FOUND(NOT_FOUND, "Not found");

    private final HttpStatus status;
    private final String defaultMessage;

    ExceptionIdentifier(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
