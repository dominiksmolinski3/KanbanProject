package pl.myproject.kanbanproject2.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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
import pl.myproject.kanbanproject2.user.SupportedLocales;
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

    @PostMapping("/signup")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterUserDto registerUserDto,
                                         HttpServletRequest request) {
        verifyCaptcha(registerUserDto.getCaptcha(), request);
        if (registerUserDto.getLocale() == null) {
            registerUserDto.setLocale(SupportedLocales.fromAcceptLanguage(
                    request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)));
        }
        authenticationService.signup(registerUserDto);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginUserDto loginDto,
                                               HttpServletRequest request) {
        verifyCaptcha(loginDto.getCaptcha(), request);
        return ResponseEntity.ok(authenticationService.login(loginDto, deviceOf(request)));
    }

    @PostMapping("/verify")
    public ResponseEntity<LoginResponse> verifyUser(@Valid @RequestBody VerifyUserDto verifyUserDto,
                                                    HttpServletRequest request) {
        return ResponseEntity.ok(authenticationService.verifyUser(verifyUserDto, deviceOf(request)));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest body,
                                                 HttpServletRequest request) {
        return ResponseEntity.ok(
                authenticationService.refresh(body.refreshToken(), deviceOf(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices")
    public ResponseEntity<List<ActiveDeviceDto>> listDevices(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(authenticationService.listSessions(currentUser));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> revokeDevice(@PathVariable Long id,
                                             @AuthenticationPrincipal User currentUser) {
        authenticationService.revokeSession(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend")
    public ResponseEntity<Void> resendVerificationCode(
            @RequestParam @NotBlank(message = "Email is required") @Email(message = "Invalid email address") String email
    ) {
        authenticationService.resendVerificationCode(email);
        return ResponseEntity.accepted().build();
    }

    private void verifyCaptcha(CaptchaDto captcha, HttpServletRequest request) {
        captchaVerifier.verify(captcha, clientIpResolver.resolve(request));
    }

    private DeviceContext deviceOf(HttpServletRequest request) {
        return new DeviceContext(
                clientIpResolver.resolve(request), request.getHeader("User-Agent"));
    }
}
