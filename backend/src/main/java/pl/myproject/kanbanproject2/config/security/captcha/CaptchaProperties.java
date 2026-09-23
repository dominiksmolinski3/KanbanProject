package pl.myproject.kanbanproject2.config.security.captcha;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "security.captcha")
public record CaptchaProperties(

        @DefaultValue("false") boolean enabled,

        @DefaultValue("") String secret,

        @DefaultValue("https://www.google.com/recaptcha/api/siteverify") String verifyUrl,

        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
