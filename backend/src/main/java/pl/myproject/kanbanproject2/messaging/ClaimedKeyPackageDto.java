package pl.myproject.kanbanproject2.messaging;

public record ClaimedKeyPackageDto(Long deviceId, Integer userId, byte[] keyPackage) {
}
