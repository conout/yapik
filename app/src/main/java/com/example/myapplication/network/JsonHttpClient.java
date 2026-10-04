package com.example.myapplication.network;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public final class JsonHttpClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    public JSONObject get(String url, Map<String, String> headers) throws IOException, JSONException {
        return execute("GET", url, null, headers);
    }

    public JSONObject postForm(String url, Map<String, String> form) throws IOException, JSONException {
        return execute("POST", url, encodeForm(form), Collections.emptyMap());
    }

    public JSONObject postForm(
            String url,
            Map<String, String> form,
            Map<String, String> headers
    ) throws IOException, JSONException {
        return execute("POST", url, encodeForm(form), headers);
    }

    private JSONObject execute(
            String method,
            String url,
            String requestBody,
            Map<String, String> headers
    ) throws IOException, JSONException {
        try {
            return executeOnce(method, url, requestBody, headers);
        } catch (IOException error) {
            if (!(error instanceof HttpException)
                    && url.startsWith("https://api.music.yandex.net/")) {
                return executeOnce(
                        method,
                        url.replace(
                                "https://api.music.yandex.net/",
                                "https://api.music.yandex.ru/"
                        ),
                        requestBody,
                        headers
                );
            }
            throw error;
        }
    }

    private JSONObject executeOnce(
            String method,
            String url,
            String requestBody,
            Map<String, String> headers
    ) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "ru");
            connection.setRequestProperty("User-Agent", "Yandex-Music-API");
            connection.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621");

            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            if (requestBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                );
                byte[] bytes = requestBody.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readBody(stream);
            JSONObject response = responseBody.isEmpty()
                    ? new JSONObject()
                    : new JSONObject(responseBody);

            if (statusCode < 200 || statusCode >= 300) {
                throw createHttpException(statusCode, response);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private String encodeForm(Map<String, String> form) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(urlEncode(entry.getKey()));
            result.append('=');
            result.append(urlEncode(entry.getValue()));
        }
        return result.toString();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private HttpException createHttpException(int statusCode, JSONObject response) {
        String errorCode = response.optString("error", "http_error");
        String message = response.optString("error_description", "");

        JSONObject nestedError = response.optJSONObject("error");
        if (nestedError != null) {
            errorCode = nestedError.optString("name", errorCode);
            message = nestedError.optString("message", message);
        }

        if (message.isEmpty()) {
            message = response.optString("errorDescription", "HTTP " + statusCode);
        }
        return new HttpException(statusCode, errorCode, message);
    }
}
