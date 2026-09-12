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
 * Where Azure tells this application what became of a message it accepted.
 *
 * <p>Everything else under {@code /api} is called by this project's own browser code holding a
 * token. This one is called by Event Grid, which has no account here and never will, and that makes
 * it the only unauthenticated <em>write</em> in the application. Four things are load-bearing
 * because of it.
 *
 * <p><b>A key in the URL, and no key means no endpoint.</b> Event Grid cannot present a bearer
 * token for a user that does not exist; what it can do is call a URL somebody gave it, so the URL
 * is the credential - the same shape as a webhook secret anywhere else. With
 * {@code app.mail.delivery-report-key} unset the route answers 404 to everything, which is the
 * state of every fresh clone and every CI run: the feature is off by default and there is no
 * unauthenticated write reachable at all. Comparison is {@link MessageDigest#isEqual}, not
 * {@code equals}, because a key compared byte by byte with an early exit can be guessed one byte
 * at a time.
 *
 * <p><b>404 rather than 401 or 403.</b> The house rule for "you may not see this" everywhere in
 * this application, and it applies here for the usual reason: a 401 confirms the endpoint exists
 * and is worth attacking, and there is nobody to tell apart a wrong key from a wrong URL for.
 * Whoever is wiring up the subscription is looking at the key they pasted in, not at a status code.
 *
 * <p><b>The handshake is the whole of why a POST must be answered with a body.</b> Event Grid will
 * not deliver anything to an endpoint until the endpoint has echoed back a code it sends first -
 * that is what stops a subscription being pointed at a URL somebody else owns. Answering 200 with
 * an empty body to that first request creates a subscription that exists and never delivers, and
 * nothing complains: the symptom is a delivery report feature that is simply always empty.
 *
 * <p><b>It always answers 2xx once the key is right.</b> Event Grid reads anything else as "try
 * again" and has its own retry schedule; a report about a message this deployment never sent -
 * ordinary, for rows older than {@code V16} or for a subscription aimed at another environment -
 * must not be retried a hundred times to be ignored a hundred times.
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

        // The handshake comes on its own and has to be answered before anything else is looked at:
        // Azure sends it once, at subscription time, and reads the body rather than the status.
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
            // Anything else is an event type this endpoint was not subscribed for. Ignored on
            // purpose and without complaint: a subscription filter is a thing somebody edits in
            // Azure, and an application that refused the result would turn that edit into an
            // outage of its own.
        }
        return ResponseEntity.ok().build();
    }

    /**
     * The endpoint is closed unless a key is configured, and closed to anyone without it.
     *
     * <p>Blank configuration is checked first and separately, or a caller presenting an empty key
     * against an unconfigured deployment would match.
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
