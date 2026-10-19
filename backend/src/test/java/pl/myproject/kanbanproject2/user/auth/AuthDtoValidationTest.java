package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDtoValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static RegisterUserDto register(String username, String email, String password) {
        var dto = new RegisterUserDto();
        dto.setUsername(username);
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private static LoginUserDto login(String email, String password) {
        var dto = new LoginUserDto();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private static VerifyUserDto verify(String email, String code) {
        return verify(email, code, "correct-horse");
    }

    private static VerifyUserDto verify(String email, String code, String password) {
        var dto = new VerifyUserDto();
        dto.setEmail(email);
        dto.setVerificationCode(code);
        dto.setPassword(password);
        return dto;
    }

    @Test
    @DisplayName("a well-formed signup passes every constraint")
    void validSignupPasses() {
        assertThat(validator.validate(register("anna", "anna@example.test", "correct-horse"))).isEmpty();
    }

    @Test
    @DisplayName("signup refuses a malformed email")
    void signupRefusesABadEmail() {
        assertThat(validator.validate(register("anna", "not-an-email", "correct-horse")))
                .isNotEmpty();
    }

    @Test
    @DisplayName("signup refuses a blank email and a blank password")
    void signupRefusesBlanks() {
        assertThat(validator.validate(register("anna", "  ", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(register("anna", "anna@example.test", "  "))).isNotEmpty();
    }

    @Test
    @DisplayName("signup refuses a password under 8 characters")
    void signupRefusesAShortPassword() {
        assertThat(validator.validate(register("anna", "anna@example.test", "short12")))
                .isNotEmpty();
    }

    @Test
    @DisplayName("signup refuses a password over 72 characters - BCrypt would truncate it")
    void signupRefusesALongPassword() {
        assertThat(validator.validate(register("anna", "anna@example.test", "x".repeat(73))))
                .isNotEmpty();
    }

    @Test
    @DisplayName("the password bounds are inclusive at both ends")
    void passwordBoundsAreInclusive() {
        assertThat(validator.validate(register("anna", "anna@example.test", "x".repeat(8)))).isEmpty();
        assertThat(validator.validate(register("anna", "anna@example.test", "x".repeat(72)))).isEmpty();
    }

    @Test
    @DisplayName("login carries the same email and password constraints as signup")
    void loginConstraintsMatch() {
        assertThat(validator.validate(login("anna@example.test", "correct-horse"))).isEmpty();
        assertThat(validator.validate(login("not-an-email", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(login("anna@example.test", "short12"))).isNotEmpty();
    }

    @Test
    @DisplayName("verification requires an email, a code and the password chosen at signup")
    void verifyRequiresAllThreeFields() {
        assertThat(validator.validate(verify("anna@example.test", "123456"))).isEmpty();
        assertThat(validator.validate(verify("anna@example.test", "  "))).isNotEmpty();
        assertThat(validator.validate(verify("  ", "123456"))).isNotEmpty();
        assertThat(validator.validate(verify("anna@example.test", "123456", null))).isNotEmpty();
        assertThat(validator.validate(verify("anna@example.test", "123456", "  "))).isNotEmpty();
    }

    @Test
    @DisplayName("verification carries the same password bounds as signup and login")
    void verifyPasswordBoundsMatch() {
        assertThat(validator.validate(verify("anna@example.test", "123456", "short12"))).isNotEmpty();
        assertThat(validator.validate(verify("anna@example.test", "123456", "x".repeat(73)))).isNotEmpty();
        assertThat(validator.validate(verify("anna@example.test", "123456", "x".repeat(72)))).isEmpty();
    }
}
