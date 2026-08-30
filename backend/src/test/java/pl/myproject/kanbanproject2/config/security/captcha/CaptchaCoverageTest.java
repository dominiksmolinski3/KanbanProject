package pl.myproject.kanbanproject2.config.security.captcha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.controller.AuthenticationController;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ways this could go back to being decorative, checked at build time.
 *
 * <p>The first is the one it was in: a client sending {@code captcha: { token }} at a DTO with no
 * such field. Spring Boot disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so that is not an error
 * anywhere - the token is dropped in silence and the widget goes on looking like a control. A
 * field being deleted, renamed, or a third credential route being added without one, all land in
 * exactly the same silence.
 *
 * <p>The second is a handler that takes a body carrying a token and never asks anyone about it.
 * Whether {@code verify} is reached is asserted by {@code AuthenticationControllerHttpTest} through
 * a real request; what this adds is that no <em>new</em> route can quietly skip the step, because
 * a route whose body carries a captcha has to be in the list below.
 */
class CaptchaCoverageTest {

    /** The routes the widget covers on the client, and therefore the ones that must verify. */
    private static final String[] CAPTCHA_ROUTES = {"login", "register"};

    @Test
    @DisplayName("both credential DTOs carry the captcha field the client has always sent")
    void bothCredentialDtosCarryTheToken() {
        assertThat(captchaFieldOf(LoginUserDto.class)).isNotNull();
        assertThat(captchaFieldOf(RegisterUserDto.class)).isNotNull();
    }

    @Test
    @DisplayName("the field is the shape authService.js posts, so no client change is needed")
    void theFieldMatchesTheClientPayload() {
        assertThat(captchaFieldOf(LoginUserDto.class).getType()).isEqualTo(CaptchaDto.class);
        assertThat(captchaFieldOf(RegisterUserDto.class).getType()).isEqualTo(CaptchaDto.class);
        assertThat(CaptchaDto.class.getRecordComponents()[0].getName()).isEqualTo("token");
    }

    @Test
    @DisplayName("every handler taking a body with a captcha is one of the routes that verifies")
    void noRouteTakesATokenWithoutVerifying() {
        var takingACaptcha = Arrays.stream(AuthenticationController.class.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(CaptchaCoverageTest::carriesACaptcha))
                .map(java.lang.reflect.Method::getName)
                .filter(name -> Arrays.stream(CAPTCHA_ROUTES).noneMatch(name::equals))
                .toList();

        assertThat(takingACaptcha)
                .as("this handler accepts a captcha token and is not known to check one; "
                        + "call verifyCaptcha or take a body that does not carry a token")
                .isEmpty();
    }

    private static boolean carriesACaptcha(Class<?> parameterType) {
        return captchaFieldOf(parameterType) != null;
    }

    private static Field captchaFieldOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.getType() == CaptchaDto.class)
                .findFirst()
                .orElse(null);
    }
}
