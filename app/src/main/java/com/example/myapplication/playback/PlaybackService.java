package com.example.myapplication.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.example.myapplication.MainActivity;
import com.example.myapplication.R;
import com.example.myapplication.auth.AuthToken;
import com.example.myapplication.auth.YandexAuthService;
import com.example.myapplication.data.YandexStreamResolver;
import com.example.myapplication.model.Episode;
import com.example.myapplication.network.HttpException;
import com.example.myapplication.network.JsonHttpClient;
import com.example.myapplication.storage.EpisodeStore;
import com.example.myapplication.storage.TokenStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.example.myapplication.playback.TOGGLE";
    public static final String ACTION_PREVIOUS = "com.example.myapplication.playback.PREVIOUS";
    public static final String ACTION_NEXT = "com.example.myapplication.playback.NEXT";
    public static final String ACTION_STOP = "com.example.myapplication.playback.STOP";

    public static final int STATE_IDLE = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_ERROR = 4;

    private static final String TAG = "PodcastPlayer";
    private static final String CHANNEL_ID = "podcast_playback";
    private static final int NOTIFICATION_ID = 4107;
    private static final long SEEK_STEP_MS = 15_000;
    private static final long SAVE_INTERVAL_MS = 15_000;

    private final LocalBinder binder = new LocalBinder();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && prepared) {
                notifyListeners();
                long now = System.currentTimeMillis();
                if (state == STATE_PLAYING && now - lastSavedAt >= SAVE_INTERVAL_MS) {
                    saveProgress(false);
                    lastSavedAt = now;
                }
            }
            mainHandler.postDelayed(this, 1_000);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private EpisodeStore episodeStore;
    private TokenStore tokenStore;
    private YandexAuthService authService;
    private YandexStreamResolver streamResolver;
    private List<Episode> queue = Collections.emptyList();
    private int queueIndex = -1;
    private String podcastTitle = "";
    private String podcastDescription = "";
    private String contentType = "podcast";
    private String artworkUri;
    private Bitmap artwork;
    private boolean artworkLoading;
    private int artworkGeneration;
    private int state = STATE_IDLE;
    private boolean prepared;
    private boolean resumeOnFocusGain;
    private String errorMessage;
    private float playbackSpeed = 1f;
    private int loadGeneration;
    private long lastSavedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        JsonHttpClient httpClient = new JsonHttpClient();
        episodeStore = new EpisodeStore(this);
        tokenStore = new TokenStore(this);
        authService = new YandexAuthService(httpClient);
        streamResolver = new YandexStreamResolver(httpClient);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(this::onAudioFocusChanged, mainHandler)
                .setWillPauseWhenDucked(true)
                .build();

        createNotificationChannel();
        createMediaSession();
        registerReceiver(
                noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED
        );
        mainHandler.post(progressTicker);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_TOGGLE:
                    toggle();
                    break;
                case ACTION_PREVIOUS:
                    previous();
                    break;
                case ACTION_NEXT:
                    next();
                    break;
                case ACTION_STOP:
                    stopPlayback();
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    public void playQueue(
            List<Episode> episodes,
            int selectedIndex,
            String title,
            String description,
            String contentType,
            String coverUri
    ) {
        if (episodes == null || episodes.isEmpty()
                || selectedIndex < 0 || selectedIndex >= episodes.size()) {
            return;
        }
        startService(new Intent(this, PlaybackService.class));
        Episode selected = episodes.get(selectedIndex);
        Episode current = currentEpisode();
        boolean sameEpisode = current != null
                && current.getPodcastId() == selected.getPodcastId()
                && current.getId().equals(selected.getId());
        if (!sameEpisode) {
            saveProgress(false);
        }
        queue = new ArrayList<>(episodes);
        queueIndex = selectedIndex;
        podcastTitle = title == null ? "" : title;
        podcastDescription = description == null ? "" : description;
        this.contentType = "audiobook".equals(contentType) ? "audiobook" : "podcast";
        loadArtwork(coverUri);
        if (sameEpisode && prepared) {
            play();
            return;
        }
        loadCurrentEpisode();
    }

    public void toggle() {
        if (state == STATE_LOADING) {
            return;
        }
        if (mediaPlayer != null && prepared && mediaPlayer.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void play() {
        if (currentEpisode() == null) {
            return;
        }
        if (!prepared) {
            loadCurrentEpisode();
            return;
        }
        if (!requestAudioFocus()) {
            showError(getString(R.string.audio_focus_error));
            return;
        }
        try {
            mediaPlayer.start();
            state = STATE_PLAYING;
            errorMessage = null;
            updateSessionAndNotification();
            notifyListeners();
        } catch (IllegalStateException error) {
            showError(getString(R.string.playback_error));
        }
    }

    public void pause() {
        pauseInternal(true);
    }

    private void pauseInternal(boolean abandonFocus) {
        if (mediaPlayer != null && prepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
                return;
            }
            state = STATE_PAUSED;
            saveProgress(false);
            if (abandonFocus) {
                abandonAudioFocus();
            }
            updateSessionAndNotification();
            notifyListeners();
        }
    }

    public void seekBy(long differenceMs) {
        seekTo(getPosition() + differenceMs);
    }

    public void seekTo(long positionMs) {
        if (mediaPlayer == null || !prepared) {
            return;
        }
        long duration = getDuration();
        long target = Math.max(0, duration > 0 ? Math.min(duration, positionMs) : positionMs);
        mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        saveProgress(false);
        updateSessionAndNotification();
        notifyListeners();
    }

    public void previous() {
        if (getPosition() > 10_000) {
            seekTo(0);
            return;
        }
        if (queueIndex > 0) {
            saveProgress(false);
            queueIndex--;
            loadCurrentEpisode();
        }
    }

    public void next() {
        if (queueIndex >= 0 && queueIndex + 1 < queue.size()) {
            saveProgress(false);
            queueIndex++;
            loadCurrentEpisode();
        }
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = Math.max(0.5f, Math.min(2f, speed));
        applyPlaybackSpeed();
        saveProgress(false);
        updateSessionAndNotification();
        notifyListeners();
    }

    public Snapshot getSnapshot() {
        Episode episode = currentEpisode();
        return new Snapshot(
                episode == null ? null : episode.getId(),
                episode == null ? 0 : episode.getPodcastId(),
                episode == null ? "" : episode.getTitle(),
                podcastTitle,
                podcastDescription,
                contentType,
                artworkUri,
                getPosition(),
                getDuration(),
                state,
                playbackSpeed,
                errorMessage,
                queueIndex > 0,
                queueIndex >= 0 && queueIndex + 1 < queue.size()
        );
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(getSnapshot());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void loadCurrentEpisode() {
        Episode episode = currentEpisode();
        if (episode == null) {
            return;
        }
        int generation = ++loadGeneration;
        releasePlayer();
        prepared = false;
        state = STATE_LOADING;
        errorMessage = null;
        updateMetadata();
        startForeground(NOTIFICATION_ID, buildNotification());
        updateMediaSessionState();
        notifyListeners();

        networkExecutor.execute(() -> {
            try {
                AuthToken token = tokenStore.load();
                if (token == null) {
                    throw new IllegalStateException("Authorization token is unavailable");
                }
                if (token.isExpired()) {
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                }
                YandexStreamResolver.StreamInfo stream;
                try {
                    stream = streamResolver.resolve(token.getAccessToken(), episode.getId());
                } catch (HttpException error) {
                    if (error.getStatusCode() != 401) {
                        throw error;
                    }
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                    stream = streamResolver.resolve(token.getAccessToken(), episode.getId());
                }
                String streamUrl = stream.getUrl();
                mainHandler.post(() -> {
                    if (generation == loadGeneration) {
                        preparePlayer(streamUrl, episode);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Unable to resolve stream", error);
                mainHandler.post(() -> {
                    if (generation == loadGeneration) {
                        showError(getString(R.string.playback_load_error));
                    }
                });
            }
        });
    }

    private void preparePlayer(String streamUrl, Episode episode) {
        releasePlayer();
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        player.setOnPreparedListener(preparedPlayer -> {
            if (preparedPlayer != mediaPlayer) {
                return;
            }
            prepared = true;
            EpisodeStore.PlaybackProgress progress = episodeStore.getPlaybackProgress(
                    episode.getPodcastId(), episode.getId()
            );
            playbackSpeed = progress.getSpeed();
            long resumePosition = progress.isCompleted() ? 0 : progress.getPositionMs();
            if (resumePosition > 0 && resumePosition < preparedPlayer.getDuration()) {
                preparedPlayer.seekTo(resumePosition, MediaPlayer.SEEK_CLOSEST);
            }
            applyPlaybackSpeed();
            updateMetadata();
            play();
        });
        player.setOnCompletionListener(completedPlayer -> {
            saveProgress(true);
            if (queueIndex + 1 < queue.size()) {
                queueIndex++;
                loadCurrentEpisode();
            } else {
                state = STATE_PAUSED;
                abandonAudioFocus();
                updateSessionAndNotification();
                notifyListeners();
            }
        });
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
            showError(getString(R.string.playback_error));
            return true;
        });
        try {
            player.setDataSource(streamUrl);
            player.prepareAsync();
        } catch (Exception error) {
            Log.e(TAG, "Unable to prepare stream", error);
            showError(getString(R.string.playback_error));
        }
    }

    private void stopPlayback() {
        ++loadGeneration;
        saveProgress(false);
        releasePlayer();
        abandonAudioFocus();
        state = STATE_IDLE;
        errorMessage = null;
        queue = Collections.emptyList();
        queueIndex = -1;
        mediaSession.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyListeners();
        stopSelf();
    }

    private void saveProgress(boolean forceCompleted) {
        Episode episode = currentEpisode();
        if (episode == null || mediaPlayer == null || !prepared) {
            return;
        }
        long position = getPosition();
        long duration = getDuration();
        boolean completed = forceCompleted;
        episodeStore.savePlaybackProgress(
                episode.getPodcastId(), episode.getId(),
                completed ? duration : position, duration, completed, playbackSpeed
        );
        episodeStore.setLastOpenedEpisode(episode.getPodcastId(), episode.getId());
    }

    private void showError(String message) {
        releasePlayer();
        abandonAudioFocus();
        prepared = false;
        state = STATE_ERROR;
        errorMessage = message;
        updateSessionAndNotification();
        notifyListeners();
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "PodcastPlayback");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long position) {
                seekTo(position);
            }

            @Override
            public void onRewind() {
                seekBy(-SEEK_STEP_MS);
            }

            @Override
            public void onFastForward() {
                seekBy(SEEK_STEP_MS);
            }

            @Override
            public void onStop() {
                stopPlayback();
            }
        });
        mediaSession.setActive(true);
    }

    private void updateMetadata() {
        Episode episode = currentEpisode();
        if (episode == null) {
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, episode.getTitle())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, podcastTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, podcastTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration());
        if (artworkUri != null) {
            builder.putString(MediaMetadata.METADATA_KEY_ART_URI, artworkUri)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUri)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artworkUri);
        }
        if (artwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        mediaSession.setMetadata(builder.build());
    }

    private void updateSessionAndNotification() {
        updateMediaSessionState();
        if (currentEpisode() != null) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void updateMediaSessionState() {
        int sessionState;
        if (state == STATE_PLAYING) {
            sessionState = PlaybackState.STATE_PLAYING;
        } else if (state == STATE_LOADING) {
            sessionState = PlaybackState.STATE_BUFFERING;
        } else if (state == STATE_ERROR) {
            sessionState = PlaybackState.STATE_ERROR;
        } else if (state == STATE_PAUSED) {
            sessionState = PlaybackState.STATE_PAUSED;
        } else {
            sessionState = PlaybackState.STATE_NONE;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(
                        PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SEEK_TO
                                | PlaybackState.ACTION_REWIND
                                | PlaybackState.ACTION_FAST_FORWARD
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                | PlaybackState.ACTION_STOP
                )
                .setState(
                        sessionState,
                        getPosition(),
                        state == STATE_PLAYING ? playbackSpeed : 0f
                );
        if (errorMessage != null) {
            builder.setErrorMessage(errorMessage);
        }
        mediaSession.setPlaybackState(builder.build());
        mediaSession.setActive(state != STATE_IDLE);
    }

    private Notification buildNotification() {
        Episode episode = currentEpisode();
        String title = episode == null ? getString(R.string.app_name) : episode.getTitle();
        String subtitle = state == STATE_LOADING
                ? getString(R.string.preparing_playback)
                : podcastTitle;
        Intent contentIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        int playIcon = state == STATE_PLAYING
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        String playTitle = state == STATE_PLAYING
                ? getString(R.string.pause)
                : getString(R.string.play);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(contentPendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(state == STATE_PLAYING || state == STATE_LOADING)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        getString(R.string.previous),
                        serviceIntent(ACTION_PREVIOUS, 1)
                ).build())
                .addAction(new Notification.Action.Builder(
                        playIcon,
                        playTitle,
                        serviceIntent(ACTION_TOGGLE, 2)
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        getString(R.string.next),
                        serviceIntent(ACTION_NEXT, 3)
                ).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (artwork != null) {
            builder.setLargeIcon(artwork);
        }
        return builder.build();
    }

    private void loadArtwork(String source) {
        String normalizedUri = normalizeArtworkUrl(source);
        boolean sameUri = normalizedUri == null
                ? artworkUri == null
                : normalizedUri.equals(artworkUri);
        if (sameUri && (normalizedUri == null || artwork != null || artworkLoading)) {
            return;
        }
        artworkUri = normalizedUri;
        artwork = null;
        artworkLoading = normalizedUri != null;
        int generation = ++artworkGeneration;
        if (normalizedUri == null) {
            return;
        }
        artworkExecutor.execute(() -> {
            Bitmap downloaded = downloadArtwork(normalizedUri);
            mainHandler.post(() -> {
                if (generation != artworkGeneration || !normalizedUri.equals(artworkUri)) {
                    return;
                }
                artworkLoading = false;
                artwork = downloaded;
                updateMetadata();
                updateSessionAndNotification();
            });
        });
    }

    private String normalizeArtworkUrl(String source) {
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

    private Bitmap downloadArtwork(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "My Podcasts Android");
            connection.setInstanceFollowRedirects(true);
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                return scaleArtwork(bitmap, 400);
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to load artwork", error);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Bitmap scaleArtwork(Bitmap bitmap, int maxSize) {
        if (bitmap == null || (bitmap.getWidth() <= maxSize && bitmap.getHeight() <= maxSize)) {
            return bitmap;
        }
        float scale = Math.min(
                maxSize / (float) bitmap.getWidth(),
                maxSize / (float) bitmap.getHeight()
        );
        return Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)),
                true
        );
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        return PendingIntent.getService(
                this,
                requestCode,
                new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.playback_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private boolean requestAudioFocus() {
        return audioManager.requestAudioFocus(audioFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
    }

    private void onAudioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                play();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain = state == STATE_PLAYING;
            pauseInternal(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeOnFocusGain = state == STATE_PLAYING;
            pauseInternal(false);
        }
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer == null || !prepared) {
            return;
        }
        try {
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(playbackSpeed);
            mediaPlayer.setPlaybackParams(params);
        } catch (IllegalStateException error) {
            Log.w(TAG, "Unable to set speed", error);
        }
    }

    private Episode currentEpisode() {
        return queueIndex >= 0 && queueIndex < queue.size() ? queue.get(queueIndex) : null;
    }

    private long getPosition() {
        if (mediaPlayer == null || !prepared) {
            return 0;
        }
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    private long getDuration() {
        if (mediaPlayer == null || !prepared) {
            Episode episode = currentEpisode();
            return episode == null ? 0 : episode.getDurationMs();
        }
        try {
            return mediaPlayer.getDuration();
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {
                // The player is already unusable; release is best effort.
            }
            mediaPlayer = null;
        }
        prepared = false;
    }

    private void notifyListeners() {
        Snapshot snapshot = getSnapshot();
        for (Listener listener : listeners) {
            listener.onPlaybackChanged(snapshot);
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(progressTicker);
        unregisterReceiver(noisyReceiver);
        saveProgress(false);
        releasePlayer();
        abandonAudioFocus();
        mediaSession.release();
        episodeStore.close();
        networkExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        super.onDestroy();
    }

    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    public interface Listener {
        void onPlaybackChanged(Snapshot snapshot);
    }

    public static final class Snapshot {

        private final String episodeId;
        private final long podcastId;
        private final String episodeTitle;
        private final String podcastTitle;
        private final String podcastDescription;
        private final String contentType;
        private final String artworkUri;
        private final long positionMs;
        private final long durationMs;
        private final int state;
        private final float speed;
        private final String errorMessage;
        private final boolean hasPrevious;
        private final boolean hasNext;

        Snapshot(
                String episodeId,
                long podcastId,
                String episodeTitle,
                String podcastTitle,
                String podcastDescription,
                String contentType,
                String artworkUri,
                long positionMs,
                long durationMs,
                int state,
                float speed,
                String errorMessage,
                boolean hasPrevious,
                boolean hasNext
        ) {
            this.episodeId = episodeId;
            this.podcastId = podcastId;
            this.episodeTitle = episodeTitle;
            this.podcastTitle = podcastTitle;
            this.podcastDescription = podcastDescription;
            this.contentType = contentType;
            this.artworkUri = artworkUri;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.state = state;
            this.speed = speed;
            this.errorMessage = errorMessage;
            this.hasPrevious = hasPrevious;
            this.hasNext = hasNext;
        }

        public String getEpisodeId() { return episodeId; }
        public long getPodcastId() { return podcastId; }
        public String getEpisodeTitle() { return episodeTitle; }
        public String getPodcastTitle() { return podcastTitle; }
        public String getPodcastDescription() { return podcastDescription; }
        public String getContentType() { return contentType; }
        public String getArtworkUri() { return artworkUri; }
        public long getPositionMs() { return positionMs; }
        public long getDurationMs() { return durationMs; }
        public float getSpeed() { return speed; }
        public String getErrorMessage() { return errorMessage; }
        public boolean hasPrevious() { return hasPrevious; }
        public boolean hasNext() { return hasNext; }
        public boolean isPlaying() { return state == STATE_PLAYING; }
        public boolean isLoading() { return state == STATE_LOADING; }
    }
}
