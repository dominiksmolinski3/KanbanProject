package pl.myproject.kanbanproject2.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.config.security.LoginResponse;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

@RequestMapping("/auth")
@RestController
@RequiredArgsConstructor
@Validated
public class AuthenticationController {

    private final AuthenticationService authenticationService;

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
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterUserDto registerUserDto) {
        authenticationService.signup(registerUserDto);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginUserDto loginDto) {
        return ResponseEntity.ok(authenticationService.login(loginDto));
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verifyUser(@Valid @RequestBody VerifyUserDto verifyUserDto) {
        authenticationService.verifyUser(verifyUserDto);
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
}
