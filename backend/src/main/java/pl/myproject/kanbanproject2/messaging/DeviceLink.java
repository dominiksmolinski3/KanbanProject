package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_links")
public class DeviceLink {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_public", nullable = false)
    private byte[] devicePublic;

    @Column(name = "linker_public")
    private byte[] linkerPublic;

    @Column(name = "sealed")
    private byte[] sealed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DeviceLink() {
    }

    public DeviceLink(User user, byte[] devicePublic, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.devicePublic = devicePublic.clone();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void answer(byte[] linkerPublic, byte[] sealed) {
        this.linkerPublic = linkerPublic.clone();
        this.sealed = sealed.clone();
    }

    public boolean isAnswered() {
        return sealed != null;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public byte[] getDevicePublic() {
        return devicePublic.clone();
    }

    public byte[] getLinkerPublic() {
        return linkerPublic == null ? null : linkerPublic.clone();
    }

    public byte[] getSealed() {
        return sealed == null ? null : sealed.clone();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
