package pl.myproject.kanbanproject2.task.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

/**
 * A file attached to a task: everything about it except the bytes.
 *
 * <p>The bytes are in Azure Blob Storage under {@link #blobName}, and this row is the only thing
 * that knows which blob is which file. That split is the point of the feature - the existing
 * {@code files} table stores uploads as a {@code @Lob} in Postgres, which puts every attachment
 * into the database's storage, its backups and its restore time, for data no query ever looks
 * inside.
 *
 * <p>There is no {@code @Version} here and no way to edit one. An attachment is written once and
 * deleted; there is no second writer for an optimistic lock to catch.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_attachments")
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The task this hangs off, which is also how it is scoped: a caller may see an attachment
     * exactly when they may see the task, and the task carries the board.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /**
     * The blob's name in the container - {@code tasks/<taskId>/<uuid>}, with no extension and
     * nothing a person typed in it.
     *
     * <p>Opaque on purpose. A blob named after the upload would put an attacker-chosen string into
     * a URL path on a storage account shared by every board here, and an extension there is a hint
     * to the service about how to serve the content. The name a person should see is
     * {@link #fileName}, and the link the browser follows carries it as a signed content
     * disposition instead.
     */
    @Column(name = "blob_name", nullable = false, unique = true, length = 200)
    private String blobName;

    /** What the uploader called it. Shown in the panel, and the name the download arrives under. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Who uploaded it. Nullable because an account can be deleted while its uploads stay on a board
     * that is still in use - the attachment belongs to the task, not to the person, and losing the
     * name is better than losing the file.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
