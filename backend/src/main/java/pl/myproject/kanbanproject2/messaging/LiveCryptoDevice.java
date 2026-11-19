package pl.myproject.kanbanproject2.messaging;

public record LiveCryptoDevice(CryptoDevice device, Long sessionId, long keyPackages) {
}
