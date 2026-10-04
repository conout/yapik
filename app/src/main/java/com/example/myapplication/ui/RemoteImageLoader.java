package com.example.myapplication.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteImageLoader {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private RemoteImageLoader() {
    }

    public static void load(ImageView imageView, View fallback, String source) {
        String url = normalizeUrl(source);
        imageView.setTag(url);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
        if (url == null) {
            return;
        }

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            showIfCurrent(imageView, fallback, url, cached);
            return;
        }

        EXECUTOR.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap != null) {
                CACHE.put(url, bitmap);
                MAIN_HANDLER.post(() -> showIfCurrent(imageView, fallback, url, bitmap));
            }
        });
    }

    private static void showIfCurrent(
            ImageView imageView,
            View fallback,
            String url,
            Bitmap bitmap
    ) {
        if (!url.equals(imageView.getTag())) {
            return;
        }
        imageView.setImageBitmap(bitmap);
        imageView.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
    }

    private static Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "My Podcasts Android");
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String normalizeUrl(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        String value = source.trim().replace("%%", "400x400");
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return "https://" + value;
        }
        return value;
    }
}
