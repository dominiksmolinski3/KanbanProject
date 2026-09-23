package pl.myproject.kanbanproject2.chat;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.LocalDateTime;

@Transactional
@Service
@Slf4j
public class ChatHistoryService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private final ChatRepository chatRepository;
    private final ChatMessageMapper mapper;
    private final BoardService boardService;

    private final int retentionDays;

    private final Clock clock;

    @Autowired
    public ChatHistoryService(ChatRepository chatRepository,
                              ChatMessageMapper mapper,
                              BoardService boardService,
                              @Value("${app.chat.retention-days:90}") int retentionDays) {
        this(chatRepository, mapper, boardService, retentionDays, Clock.systemUTC());
    }

    ChatHistoryService(ChatRepository chatRepository,
                       ChatMessageMapper mapper,
                       BoardService boardService,
                       int retentionDays,
                       Clock clock) {
        this.chatRepository = chatRepository;
        this.mapper = mapper;
        this.boardService = boardService;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    public ChatMessageResults boardHistory(User caller, Integer boardId, Integer page, Integer size) {
        int wantedPage = pageOf(page);
        int wantedSize = sizeOf(size);

        var board = boardService.resolve(caller, boardId);
        return results(chatRepository.findByBoardOrderByTimestampDescIdDesc(
                board, PageRequest.of(wantedPage, wantedSize)), wantedPage, wantedSize);
    }

    public ChatMessageResults directHistory(User caller, String peerUsername, Integer page, Integer size) {
        int wantedPage = pageOf(page);
        int wantedSize = sizeOf(size);

        if (peerUsername == null || peerUsername.isBlank()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CHAT_REQUEST,
                    "A direct thread needs the account it is with");
        }
        var peer = boardService.peersOf(caller).stream()
                .filter(user -> peerUsername.equalsIgnoreCase(user.getUsername()))
                .findFirst()
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND));

        return results(chatRepository.findDirectThread(caller.getUsername(), peer.getUsername(),
                PageRequest.of(wantedPage, wantedSize)), wantedPage, wantedSize);
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void pruneExpiredMessages() {
        if (retentionDays <= 0) {
            return;
        }
        int removed = chatRepository.deleteOlderThan(LocalDateTime.now(clock).minusDays(retentionDays));
        if (removed > 0) {
            log.info("Pruned {} chat messages older than {} days", removed, retentionDays);
        }
    }

    private ChatMessageResults results(Page<Chat> found, int page, int size) {
        return new ChatMessageResults(
                found.getContent().stream().map(mapper).toList(),
                page,
                size,
                found.getTotalElements(),
                found.getTotalPages());
    }

    private static int pageOf(Integer page) {
        int wanted = page == null ? 0 : page;
        if (wanted < 0) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CHAT_REQUEST, "page must be 0 or more");
        }
        return wanted;
    }

    private static int sizeOf(Integer size) {
        int wanted = size == null ? DEFAULT_PAGE_SIZE : size;
        if (wanted < 1 || wanted > MAX_PAGE_SIZE) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CHAT_REQUEST,
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return wanted;
    }
}
