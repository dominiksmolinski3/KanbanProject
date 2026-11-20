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
@Table(name = "push_endpoints")
public class PushEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private CryptoDevice device;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PushKind kind;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Column(name = "p256dh")
    private String p256dh;

    @Column(name = "auth")
    private String auth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushEndpoint() {
    }

    public PushEndpoint(CryptoDevice device, PushKind kind, String endpoint, String p256dh, String auth,
                        Instant createdAt) {
        this.user = device.getUser();
        this.device = device;
        this.kind = kind;
        this.createdAt = createdAt;
        replace(endpoint, p256dh, auth);
    }

    public final void replace(String endpoint, String p256dh, String auth) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public CryptoDevice getDevice() {
        return device;
    }

    public PushKind getKind() {
        return kind;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }
}
