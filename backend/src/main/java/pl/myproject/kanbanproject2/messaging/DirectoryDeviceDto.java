package pl.myproject.kanbanproject2.messaging;

public record DirectoryDeviceDto(Long id, byte[] signatureKey, byte[] credential, byte[] identitySignature,
                                 boolean revoked) {
}
