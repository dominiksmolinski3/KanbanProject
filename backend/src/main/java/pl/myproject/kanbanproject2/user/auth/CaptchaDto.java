package pl.myproject.kanbanproject2.user.auth;

/**
 * The {@code captcha} object the client has been sending since before anything read it:
 * {@code {"captcha": {"token": "..."}}}.
 *
 * <p>The shape is the client's, not a new one. {@code authService.js} has posted it on login and
 * signup all along and it was dropped on arrival, so matching it here is what turns the existing
 * request into a checked one - with no client change and no version skew between a deployed
 * frontend and this backend.
 */
public record CaptchaDto(String token) {
}
