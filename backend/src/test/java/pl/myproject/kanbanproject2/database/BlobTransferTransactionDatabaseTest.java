package pl.myproject.kanbanproject2.database;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentMapper;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.avatar.AvatarService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig({DatabaseTestConfiguration.class, BlobTransferTransactionDatabaseTest.Beans.class})
@Import({BoardService.class, BoardMapper.class, BoardEventPublisher.class, TaskAttachmentService.class,
        TaskAttachmentMapper.class, AvatarService.class})
class BlobTransferTransactionDatabaseTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    static class RecordingBlobStore implements BlobStore {
        final List<String> transfersInsideATransaction = new ArrayList<>();
        int transfers;

        private void record(String operation) {
            transfers++;
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transfersInsideATransaction.add(operation);
            }
        }

        @Override
        public void put(String blobName, String contentType, InputStream data, long length) {
            record("put " + blobName);
        }

        @Override
        public InputStream read(String blobName) {
            record("read " + blobName);
            return new ByteArrayInputStream(PNG);
        }

        @Override
        public InputStream read(String blobName, long offset, long length) {
            record("read " + blobName);
            return new ByteArrayInputStream(PNG);
        }

        @Override
        public void remove(String blobName) {
        }

        @Override
        public List<StoredBlob> list(String prefix) {
            return List.of();
        }
    }

    @Configuration
    static class Beans {
        @Bean
        RecordingBlobStore blobStore() {
            return new RecordingBlobStore();
        }

        @Bean
        BlobStorageProperties blobStorageProperties() {
            return new BlobStorageProperties("https://example.blob.core.windows.net", "", "task-attachments", "",
                    8, 1, 500, 1_073_741_824L);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SimpMessagingTemplate messagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }

    @Autowired
    private TaskAttachmentService attachmentService;

    @Autowired
    private AvatarService avatarService;

    @Autowired
    private BoardService boardService;

    @Autowired
    private RecordingBlobStore blobStore;

    @Autowired
    private UserRepository users;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private Integer taskId;

    @BeforeEach
    void setUp() {
        blobStore.transfers = 0;
        blobStore.transfersInsideATransaction.clear();
        owner = transactions.execute(status -> {
            var user = new User("owner", "owner-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
        taskId = transactions.execute(status -> {
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest("Files")).id())
                    .orElseThrow();
            var task = new Task();
            task.setTitle("with a file");
            task.setBoard(board);
            return tasks.save(task).getId();
        });
    }

    @Test
    @DisplayName("an attachment is written to and read from storage with no transaction open, and its row still commits")
    void attachmentTransfersHoldNoTransaction() throws Exception {
        var saved = attachmentService.upload(owner, taskId,
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()));
        attachmentService.content(owner, taskId, saved.id(), null).stream().close();

        assertThat(blobStore.transfers).isEqualTo(2);
        assertThat(blobStore.transfersInsideATransaction)
                .as("a transaction around the transfer holds a database connection for as long as storage takes; "
                        + "a stalled store then empties the pool and every other request waits")
                .isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from task_attachments where task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an avatar is written to and read from storage with no transaction open, and the account still records it")
    void avatarTransfersHoldNoTransaction() throws Exception {
        avatarService.upload(owner, new MockMultipartFile("file", "me.png", "image/png", PNG));
        avatarService.content(owner.getId()).stream().close();

        assertThat(blobStore.transfers).isEqualTo(2);
        assertThat(blobStore.transfersInsideATransaction).isEmpty();
        assertThat(jdbc.queryForObject("select avatar_blob_name from users where id = ?", String.class, owner.getId()))
                .startsWith("avatars/" + owner.getId() + "/");
    }
}
