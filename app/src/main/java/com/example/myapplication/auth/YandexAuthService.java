package com.example.myapplication.auth;

import com.example.myapplication.network.HttpException;
import com.example.myapplication.network.JsonHttpClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class YandexAuthService {

    private static final String CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    private static final String CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300";
    private static final String DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code";
    private static final String TOKEN_URL = "https://oauth.yandex.ru/token";
    private static final String REQUESTED_SCOPE =
            "login:avatar music:content music:read music:write";

    private final JsonHttpClient httpClient;

    public YandexAuthService(JsonHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public DeviceCode requestDeviceCode() throws IOException, JSONException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("device_id", UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        form.put("device_name", "Япик Android");
        form.put("scope", REQUESTED_SCOPE);

        JSONObject response = unwrapResult(httpClient.postForm(DEVICE_CODE_URL, form));
        return new DeviceCode(
                response.getString("device_code"),
                response.getString("user_code"),
                response.getString("verification_url"),
                response.optLong("expires_in", 600),
                response.optInt("interval", 5)
        );
    }

    public AuthToken pollDeviceToken(DeviceCode deviceCode) throws IOException, JSONException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "device_code");
        form.put("code", deviceCode.getDeviceCode());
        form.put("client_id", CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);

        try {
            JSONObject response = unwrapResult(httpClient.postForm(TOKEN_URL, form));
            return parseToken(response, null);
        } catch (HttpException error) {
            if ("authorization_pending".equals(error.getErrorCode())
                    || "slow_down".equals(error.getErrorCode())) {
                return null;
            }
            throw error;
        }
    }

    public AuthToken refreshToken(AuthToken currentToken) throws IOException, JSONException {
        if (currentToken.getRefreshToken() == null || currentToken.getRefreshToken().isEmpty()) {
            throw new IOException("Refresh token is unavailable");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", currentToken.getRefreshToken());
        form.put("client_id", CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);

        JSONObject response = unwrapResult(httpClient.postForm(TOKEN_URL, form));
        return parseToken(response, currentToken.getRefreshToken());
    }

    private AuthToken parseToken(JSONObject response, String fallbackRefreshToken) throws JSONException {
        long expiresIn = Math.max(60, response.optLong("expires_in", 3_600));
        String refreshToken = response.optString("refresh_token", fallbackRefreshToken);
        return new AuthToken(
                response.getString("access_token"),
                refreshToken,
                Instant.now().getEpochSecond() + expiresIn
        );
    }

    private JSONObject unwrapResult(JSONObject response) {
        JSONObject result = response.optJSONObject("result");
        return result == null ? response : result;
    }
}
