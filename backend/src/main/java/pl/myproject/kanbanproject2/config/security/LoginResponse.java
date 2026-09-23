package pl.myproject.kanbanproject2.config.security;

public record LoginResponse(String token, long expiresIn, String refreshToken, long refreshExpiresIn,
                            Long sessionId) {
}
