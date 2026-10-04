package com.example.myapplication.auth;

import java.time.Instant;

public final class AuthToken {

    private final String accessToken;
    private final String refreshToken;
    private final long expiresAtEpochSeconds;

    public AuthToken(String accessToken, String refreshToken, long expiresAtEpochSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    public boolean isExpired() {
        return Instant.now().getEpochSecond() + 60 >= expiresAtEpochSeconds;
    }
}
