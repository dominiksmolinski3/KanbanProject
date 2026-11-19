package pl.myproject.kanbanproject2.messaging;

import java.util.List;

public record CryptoStatusDto(boolean enabled, IdentityKeyDto identity, List<CryptoDeviceDto> devices) {
}
