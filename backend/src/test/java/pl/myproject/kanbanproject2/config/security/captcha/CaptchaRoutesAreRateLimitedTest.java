package pl.myproject.kanbanproject2.config.security.captcha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule;
import pl.myproject.kanbanproject2.controller.AuthenticationController;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptchaRoutesAreRateLimitedTest {
    @Test
    @DisplayName("every route that verifies a captcha is throttled before it can call the provider")
    void captchaRoutesAreThrottled() {
        List<String> unthrottled = captchaRoutePaths().stream()
                .filter(path -> AuthRateLimitRule.forPath(path).isEmpty())
                .toList();

        assertThat(unthrottled)
                .as("this route spends a captcha siteverify call per request and nothing limits how "
                        + "often; add it to AuthRateLimitRule, or take a body without a token")
                .isEmpty();
    }

    @Test
    @DisplayName("the captcha routes are found at all, so the check above cannot pass by finding none")
    void theRoutesAreActuallyDiscovered() {
        assertThat(captchaRoutePaths())
                .containsExactlyInAnyOrder("/api/auth/login", "/api/auth/signup");
    }

    private static List<String> captchaRoutePaths() {
        String base = AuthenticationController.class.getAnnotation(RequestMapping.class).value()[0];
        return Arrays.stream(AuthenticationController.class.getDeclaredMethods())
                .filter(CaptchaRoutesAreRateLimitedTest::takesACaptcha)
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null && mapping.value().length > 0)
                .map(mapping -> "/api" + base + mapping.value()[0])
                .toList();
    }

    private static boolean takesACaptcha(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .anyMatch(type -> Arrays.stream(type.getDeclaredFields())
                        .map(Field::getType)
                        .anyMatch(CaptchaDto.class::equals));
    }
}
