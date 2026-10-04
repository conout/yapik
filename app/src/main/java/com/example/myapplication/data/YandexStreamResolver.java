package com.example.myapplication.data;

import android.util.Base64;
import android.util.Xml;

import com.example.myapplication.network.JsonHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.xmlpull.v1.XmlPullParser;

public final class YandexStreamResolver {

    private static final String API_BASE_URL = "https://api.music.yandex.net";
    private static final String STREAM_SIGNING_KEY = "p93jhgh689SBReK6ghtw62";
    private static final String LEGACY_SIGNING_SALT = "XGRlBW9FXlekgbPrRHuSiA";

    private final JsonHttpClient httpClient;

    public YandexStreamResolver(JsonHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public StreamInfo resolve(String accessToken, String episodeId) throws Exception {
        long timestamp = System.currentTimeMillis() / 1_000L;
        String signature = createSignature(episodeId, timestamp);
        String url = API_BASE_URL
                + "/tracks/" + urlEncode(episodeId)
                + "/download-info?can_use_streaming=true&ts=" + timestamp
                + "&sign=" + urlEncode(signature);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "OAuth " + accessToken);
        JSONObject response = httpClient.get(url, headers);
        JSONArray variants = response.optJSONArray("result");
        if (variants == null || variants.length() == 0) {
            throw new JSONException("Yandex returned no stream variants");
        }

        StreamInfo best = null;
        for (int index = 0; index < variants.length(); index++) {
            JSONObject variant = variants.optJSONObject(index);
            if (variant == null || variant.optBoolean("preview", false)) {
                continue;
            }
            String streamUrl = variant.optString("downloadInfoUrl", "");
            if (streamUrl.isEmpty()) {
                continue;
            }
            StreamInfo candidate = new StreamInfo(
                    streamUrl,
                    variant.optString("container", ""),
                    variant.optString("codec", ""),
                    variant.optInt("bitrateInKbps", 0),
                    variant.optBoolean("direct", false)
            );
            if (best == null || score(candidate) > score(best)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new JSONException("Yandex returned no full stream URL");
        }
        if (best.direct) {
            return best;
        }
        return new StreamInfo(
                resolveLegacyDirectLink(best.getUrl()),
                "mp3",
                best.getCodec(),
                best.getBitrateKbps(),
                true
        );
    }

    private int score(StreamInfo info) {
        int score = info.getBitrateKbps();
        if ("hls".equalsIgnoreCase(info.getContainer())) {
            score += 10_000;
        }
        if ("aac".equalsIgnoreCase(info.getCodec())) {
            score += 1_000;
        }
        return score;
    }

    private String createSignature(String episodeId, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                STREAM_SIGNING_KEY.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));
        byte[] value = mac.doFinal(
                (episodeId + timestamp).getBytes(StandardCharsets.UTF_8)
        );
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private String resolveLegacyDirectLink(String metadataUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(metadataUrl).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "Yandex-Music-API");
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                throw new IOException("Stream metadata HTTP " + connection.getResponseCode());
            }
            String host = null;
            String path = null;
            String timestamp = null;
            String saltValue = null;
            try (InputStream input = connection.getInputStream()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(input, "UTF-8");
                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        String tag = parser.getName();
                        if ("host".equals(tag)) {
                            host = parser.nextText();
                        } else if ("path".equals(tag)) {
                            path = parser.nextText();
                        } else if ("ts".equals(tag)) {
                            timestamp = parser.nextText();
                        } else if ("s".equals(tag)) {
                            saltValue = parser.nextText();
                        }
                    }
                    event = parser.next();
                }
            }
            if (host == null || path == null || timestamp == null || saltValue == null) {
                throw new IOException("Incomplete stream metadata");
            }
            String valueToSign = LEGACY_SIGNING_SALT + path.substring(1) + saltValue;
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(valueToSign.getBytes(StandardCharsets.UTF_8));
            StringBuilder signature = new StringBuilder();
            for (byte value : hash) {
                signature.append(String.format("%02x", value & 0xff));
            }
            return "https://" + host + "/get-mp3/" + signature + "/" + timestamp + path;
        } finally {
            connection.disconnect();
        }
    }

    private String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    public static final class StreamInfo {

        private final String url;
        private final String container;
        private final String codec;
        private final int bitrateKbps;
        private final boolean direct;

        StreamInfo(
                String url,
                String container,
                String codec,
                int bitrateKbps,
                boolean direct
        ) {
            this.url = url;
            this.container = container;
            this.codec = codec;
            this.bitrateKbps = bitrateKbps;
            this.direct = direct;
        }

        public String getUrl() {
            return url;
        }

        public String getContainer() {
            return container;
        }

        public String getCodec() {
            return codec;
        }

        public int getBitrateKbps() {
            return bitrateKbps;
        }
    }
}
