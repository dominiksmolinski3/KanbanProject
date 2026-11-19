package pl.myproject.kanbanproject2.messaging;

public record IdentityBackupDto(byte[] publicKey, byte[] wrappedPrivate) {
}
