package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

@Slf4j
@Component
public class MessageBlobPrune {

    static final int BATCH = 200;

    private final MessageBlobRepository blobs;
    private final MessageBlobService service;
    private final Clock clock;

    @Autowired
    public MessageBlobPrune(MessageBlobRepository blobs, MessageBlobService service) {
        this(blobs, service, Clock.systemUTC());
    }

    MessageBlobPrune(MessageBlobRepository blobs, MessageBlobService service, Clock clock) {
        this.blobs = blobs;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 50 4 * * *")
    @Transactional
    public void prune() {
        int removed = 0;
        List<MessageBlob> batch = blobs.findExpired(clock.instant(), PageRequest.of(0, BATCH));
        while (!batch.isEmpty()) {
            batch.forEach(blob -> service.removeQuietly(blob.getBlobName()));
            blobs.deleteAll(batch);
            blobs.flush();
            removed += batch.size();
            batch = blobs.findExpired(clock.instant(), PageRequest.of(0, BATCH));
        }
        if (removed > 0) {
            log.info("Pruned {} expired message attachment(s)", removed);
        }
    }
}
