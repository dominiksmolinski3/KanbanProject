package pl.myproject.kanbanproject2.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.config.security.captcha.CaptchaVerifier;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.config.security.LoginResponse;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceDto;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.ForgotPasswordRequest;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRequest;
import pl.myproject.kanbanproject2.user.auth.ResetPasswordRequest;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

import java.util.List;

@RequestMapping("/auth")
@RestController
@RequiredArgsConstructor
@Validated
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final CaptchaVerifier captchaVerifier;
    private final ClientIpResolver clientIpResolver;

    /**
     * Answers {@code 202 Accepted} with no body, whether or not the address was new.
     *
     * <p>The body was the other half of the leak: it returned the created {@code UserDto}, so even
     * under a uniform status a caller could tell a new account from a collision by whether an id
     * came back. Nothing consumes it — the client discards the value and moves to the verification
     * screen — so there is nothing to return, and 202 is the honest status: the request was
     * accepted, and what happens next arrives by mail or does not.
     */
    @PostMapping("/signup")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterUserDto registerUserDto,
                                         HttpServletRequest request) {
        verifyCaptcha(registerUserDto.getCaptcha(), request);
        authenticationService.signup(registerUserDto);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginUserDto loginDto,
                                               HttpServletRequest request) {
        verifyCaptcha(loginDto.getCaptcha(), request);
        return ResponseEntity.ok(authenticationService.login(loginDto, deviceOf(request)));
    }

    /**
     * Answers {@code 200} with a session, the same body {@code /login} returns.
     *
     * <p>It used to be {@code 204}: the account was enabled and the caller was sent back to the
     * sign-in form to type the password it had typed two screens earlier. The code redeemed here
     * came from the mailbox and is spent in the redeeming, so it is a credential like any other,
     * and there is nothing further to prove.
     */
    @PostMapping("/verify")
    public ResponseEntity<LoginResponse> verifyUser(@Valid @RequestBody VerifyUserDto verifyUserDto,
                                                    HttpServletRequest request) {
        return ResponseEntity.ok(authenticationService.verifyUser(verifyUserDto, deviceOf(request)));
    }

    /**
     * Answers {@code 202 Accepted} whether or not the address has an account.
     *
     * <p>Needs no authentication - someone who has forgotten their password has no token - so
     * telling the caller which of the two happened would make it a membership oracle anyone could
     * walk. The person who owns the mailbox sees the difference; nobody else does.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /** Redeems a reset code. Every failure is one status, for the same reason. */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Exchanges a refresh token for a new access token, and rotates the refresh token with it.
     *
     * <p>Public, and it has to be: the caller reaching for this route is the one whose access token
     * has just lapsed, so requiring one would make the route useless exactly when it is needed. The
     * refresh token in the body is the credential, and it is checked against a row that can be
     * withdrawn - which is the difference this whole route exists for.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest body,
                                                 HttpServletRequest request) {
        return ResponseEntity.ok(
                authenticationService.refresh(body.refreshToken(), deviceOf(request)));
    }

    /**
     * Ends the session the token names, and answers {@code 204} whether or not it was live.
     *
     * <p>Before refresh tokens existed there was nothing for this route to do - a JWT is valid
     * until it expires no matter what the server thinks - so signing out was a client-side gesture
     * that deleted a token the server would still have accepted. Now it withdraws a row.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Every session the caller can still use, newest renewal first.
     *
     * <p>Not public, unlike everything above it, and it is the first route under {@code /auth} that
     * is not. The routes around it exist for someone who cannot prove who they are yet; this one
     * lists an account's sessions, so proving it is the entire precondition. It is absent from
     * {@code PublicPaths} for that reason and takes the caller like every other guarded route -
     * {@code BoardScopedRoutesTest} fails the build if it stops doing either.
     *
     * <p>Rate limiting follows the same split: the limiter covers the unauthenticated routes, where
     * the caller is an unknown address guessing at credentials. A caller here has already presented
     * a valid access token, and the thing they could hammer is a list of their own rows.
     */
    @GetMapping("/devices")
    public ResponseEntity<List<ActiveDeviceDto>> listDevices(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(authenticationService.listSessions(currentUser));
    }

    /**
     * Ends one session and answers {@code 204}; anything that is not a live session of the caller's
     * is {@code 404 SESSION_NOT_FOUND}, including somebody else's.
     *
     * <p>This is what the refresh-token table was built to make possible and what it still could
     * not do: a password change withdrew everything, and logout withdrew the session doing the
     * asking, so the one case a person actually has - a device they no longer have, and a session
     * they would rather keep - had no answer that did not sign them out everywhere.
     */
    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> revokeDevice(@PathVariable Long id,
                                             @AuthenticationPrincipal User currentUser) {
        authenticationService.revokeSession(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    /** Also {@code 202}, and for the same reason: whether a code was sent is not the caller's to know. */
    @PostMapping("/resend")
    public ResponseEntity<Void> resendVerificationCode(
            @RequestParam @NotBlank(message = "Email is required") @Email(message = "Invalid email address") String email
    ) {
        authenticationService.resendVerificationCode(email);
        return ResponseEntity.accepted().build();
    }

    /**
     * Checked here rather than in the service because the address to report to the provider is a
     * servlet fact, and {@link ClientIpResolver} is where the project already decides which address
     * a request belongs to - the same answer the rate limiter bills. It runs before the credentials
     * are looked at, so a failed challenge costs nothing downstream and tells the caller nothing
     * about the account.
     */
    private void verifyCaptcha(CaptchaDto captcha, HttpServletRequest request) {
        captchaVerifier.verify(captcha, clientIpResolver.resolve(request));
    }

    /**
     * What gets written onto the session row so its owner can recognise it later.
     *
     * <p>The address comes from {@link ClientIpResolver} rather than {@code getRemoteAddr} for the
     * same reason the captcha check above uses it: behind a proxy the socket address is the proxy's,
     * and the number of hops to trust is a deployment fact this application already has one answer
     * for. Using a second answer here would show one address in the session list and bill another
     * on the rate limit.
     */
    private DeviceContext deviceOf(HttpServletRequest request) {
        return new DeviceContext(
                clientIpResolver.resolve(request), request.getHeader("User-Agent"));
    }
}
