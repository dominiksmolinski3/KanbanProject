package pl.myproject.kanbanproject2.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.messaging.push")
public record PushProperties(@DefaultValue("") String vapidPublicKey, @DefaultValue("") String vapidPrivateKey,
                             @DefaultValue("") String vapidSubject, @DefaultValue("") String fcmCredentials) {

    public boolean webPushConfigured() {
        return !vapidPublicKey.isBlank() && !vapidPrivateKey.isBlank() && !vapidSubject.isBlank();
    }

    public boolean fcmConfigured() {
        return !fcmCredentials.isBlank();
    }
}
