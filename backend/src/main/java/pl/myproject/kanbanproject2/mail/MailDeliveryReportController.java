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

/**
 * Where Azure tells this application what became of a message it accepted. Everything else under
 * {@code /api} is called by this project's own browser code holding a token; this is called by
 * Event Grid, which has no account here, making it the only unauthenticated <em>write</em> in the
 * application.
 *
 * <p><b>A key in the URL is the credential.</b> Event Grid cannot present a bearer token, so
 * {@code app.mail.delivery-report-key} is compared with {@link MessageDigest#isEqual} (not
 * {@code equals}, to avoid a byte-at-a-time timing guess); unset, the route 404s to everything,
 * which is the state of every fresh clone and CI run. A wrong key is the same 404 as no key, the
 * usual "you may not see this" rule here - a 401 would confirm the endpoint is worth attacking.
 *
 * <p><b>The handshake is why a POST must be answered with a body.</b> Event Grid will not deliver
 * anything until the endpoint echoes back the validation code it sends first; answering 200 with an
 * empty body creates a subscription that exists and never delivers, with no visible symptom beyond
 * an always-empty feature.
 *
 * <p><b>It always answers 2xx once the key is right.</b> Event Grid reads anything else as "try
 * again"; a report naming a message this deployment never sent - ordinary for rows older than
 * {@code V16} or a subscription aimed at another environment - must not be retried into oblivion.
 */
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

        // Answered before anything else: Azure sends this once, at subscription time, and reads
        // the body rather than the status.
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
            // Any other event type is ignored without complaint: a subscription filter is edited
            // in Azure, and refusing the result would turn that edit into an outage.
        }
        return ResponseEntity.ok().build();
    }

    /**
     * The endpoint is closed unless a key is configured, and closed to anyone without it. Blank
     * configuration is checked first, or a caller presenting an empty key against an unconfigured
     * deployment would match.
     */
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
