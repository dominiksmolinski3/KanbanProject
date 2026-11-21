package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
public class DeviceLinkPrune {

    private final DeviceLinkRepository links;
    private final Clock clock;

    @Autowired
    public DeviceLinkPrune(DeviceLinkRepository links) {
        this(links, Clock.systemUTC());
    }

    DeviceLinkPrune(DeviceLinkRepository links, Clock clock) {
        this.links = links;
        this.clock = clock;
    }

    @Scheduled(cron = "0 55 * * * *")
    @Transactional
    public void prune() {
        int deleted = links.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Pruned {} expired device link(s)", deleted);
        }
    }
}
