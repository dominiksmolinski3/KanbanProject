package pl.myproject.kanbanproject2.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.AcsMailProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/mail/delivery-reports")
public class MailDeliveryReportController {

    private final MailDeliveryReportService reports;
    private final AcsMailProperties mail;

    public MailDeliveryReportController(MailDeliveryReportService reports, AcsMailProperties mail) {
        this.reports = reports;
        this.mail = mail;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> receive(
            @RequestParam(name = "key", required = false) String key,
            @RequestBody List<EventGridNotification> notifications) {

        requireKey(key);

        if (notifications == null || notifications.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        for (EventGridNotification notification : notifications) {
            if (notification != null && notification.isValidation()) {
                String code = notification.data() == null ? null : notification.data().validationCode();
                log.info("Event Grid is validating the delivery-report subscription");
                return ResponseEntity.ok(Map.of("validationResponse", code == null ? "" : code));
            }
        }

        for (EventGridNotification notification : notifications) {
            if (notification != null && notification.isDeliveryReport()) {
                reports.record(notification.data());
            }
        }
        return ResponseEntity.ok().build();
    }

    private void requireKey(String presented) {
        String expected = mail.deliveryReportKey();
        if (expected == null || expected.isBlank() || presented == null) {
            throw new GlobalException(ExceptionIdentifier.MAIL_DELIVERY_REPORT_NOT_FOUND);
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw new GlobalException(ExceptionIdentifier.MAIL_DELIVERY_REPORT_NOT_FOUND);
        }
    }
}
