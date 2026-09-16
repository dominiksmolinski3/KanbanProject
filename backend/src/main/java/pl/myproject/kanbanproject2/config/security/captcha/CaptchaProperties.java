package pl.myproject.kanbanproject2.config.security.captcha;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Whether the captcha token the client sends is actually checked, and what to check it against.
 * {@code enabled} doubles as the escape hatch: a token that cannot be checked is refused rather
 * than waved through (see {@link CaptchaVerifier}), so a captcha provider outage would otherwise
 * become an outage here too.
 */
@ConfigurationProperties(prefix = "security.captcha")
public record CaptchaProperties(

        /* Off by default: a deployment that has not set a secret must not think it has a control. */
        @DefaultValue("false") boolean enabled,

        /* The shared secret from the reCAPTCHA admin console. Required when enabled. */
        @DefaultValue("") String secret,

        @DefaultValue("https://www.google.com/recaptcha/api/siteverify") String verifyUrl,

        /*
         * Both halves of the budget a login is allowed to spend waiting for the provider. A login
         * holds a request thread while this runs, so an unbounded wait here is a way to exhaust
         * the pool from outside.
         */
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
