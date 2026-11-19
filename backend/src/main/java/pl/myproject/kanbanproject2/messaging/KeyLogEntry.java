package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

@Entity
@Table(name = "key_log")
public class KeyLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "change", nullable = false, length = 16)
    private KeyChange change;

    @Column(name = "subject", nullable = false)
    private byte[] subject;

    @Column(name = "entry_hash", nullable = false)
    private byte[] entryHash;

    @Column(name = "prev_hash")
    private byte[] prevHash;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected KeyLogEntry() {
    }

    public KeyLogEntry(User user, KeyChange change, byte[] subject, byte[] prevHash, byte[] entryHash, Instant at) {
        this.user = user;
        this.change = change;
        this.subject = subject.clone();
        this.prevHash = prevHash == null ? null : prevHash.clone();
        this.entryHash = entryHash.clone();
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public KeyChange getChange() {
        return change;
    }

    public byte[] getSubject() {
        return subject.clone();
    }

    public byte[] getEntryHash() {
        return entryHash.clone();
    }

    public byte[] getPrevHash() {
        return prevHash == null ? null : prevHash.clone();
    }

    public Instant getAt() {
        return at;
    }
}
