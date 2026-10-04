package com.example.myapplication.auth;

import android.os.SystemClock;

public final class DeviceCode {

    private final String deviceCode;
    private final String userCode;
    private final String verificationUrl;
    private final long expiresAtElapsedMs;
    private final int pollIntervalSeconds;

    public DeviceCode(
            String deviceCode,
            String userCode,
            String verificationUrl,
            long expiresInSeconds,
            int pollIntervalSeconds
    ) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.verificationUrl = verificationUrl;
        this.expiresAtElapsedMs = SystemClock.elapsedRealtime() + expiresInSeconds * 1_000L;
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public String getVerificationUrl() {
        return verificationUrl;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public boolean isExpired() {
        return SystemClock.elapsedRealtime() >= expiresAtElapsedMs;
    }
}
