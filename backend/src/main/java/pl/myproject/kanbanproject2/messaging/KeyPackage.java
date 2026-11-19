package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "key_packages")
public class KeyPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private CryptoDevice device;

    @Column(name = "ref", nullable = false, unique = true)
    private byte[] ref;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "last_resort", nullable = false)
    private boolean lastResort;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KeyPackage() {
    }

    public KeyPackage(CryptoDevice device, byte[] ref, byte[] data, boolean lastResort, Instant createdAt) {
        this.device = device;
        this.ref = ref.clone();
        this.data = data.clone();
        this.lastResort = lastResort;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public CryptoDevice getDevice() {
        return device;
    }

    public byte[] getRef() {
        return ref.clone();
    }

    public byte[] getData() {
        return data.clone();
    }

    public boolean isLastResort() {
        return lastResort;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
