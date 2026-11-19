package pl.myproject.kanbanproject2.messaging;

import java.util.List;

public record DirectoryEntryDto(Integer userId, byte[] identityKey, List<DirectoryDeviceDto> devices) {
}
