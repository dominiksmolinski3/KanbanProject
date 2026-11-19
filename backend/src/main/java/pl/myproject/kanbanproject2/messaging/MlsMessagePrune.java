package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
public class MlsMessagePrune {

    private final MlsMessageRepository messages;
    private final Clock clock;

    @Autowired
    public MlsMessagePrune(MlsMessageRepository messages) {
        this(messages, Clock.systemUTC());
    }

    MlsMessagePrune(MlsMessageRepository messages, Clock clock) {
        this.messages = messages;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 4 * * *")
    @Transactional
    public void prune() {
        int deleted = messages.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Pruned {} expired encrypted message(s)", deleted);
        }
    }
}
