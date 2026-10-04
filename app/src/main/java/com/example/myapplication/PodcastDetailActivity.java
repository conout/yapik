package com.example.myapplication;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.auth.AuthToken;
import com.example.myapplication.auth.YandexAuthService;
import com.example.myapplication.data.YandexMusicRepository;
import com.example.myapplication.model.Episode;
import com.example.myapplication.network.HttpException;
import com.example.myapplication.network.JsonHttpClient;
import com.example.myapplication.playback.PlaybackService;
import com.example.myapplication.storage.EpisodeStore;
import com.example.myapplication.storage.TokenStore;
import com.example.myapplication.storage.ThemeStore;
import com.example.myapplication.ui.EpisodeListAdapter;
import com.example.myapplication.ui.PlaybackPanelController;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PodcastDetailActivity extends Activity {

    public static final String EXTRA_PODCAST_ID = "podcast_id";
    public static final String EXTRA_PODCAST_TITLE = "podcast_title";
    public static final String EXTRA_PODCAST_DESCRIPTION = "podcast_description";
    public static final String EXTRA_PODCAST_COVER_URI = "podcast_cover_uri";
    public static final String EXTRA_PODCAST_FAVORITE = "podcast_favorite";
    public static final String EXTRA_EPISODE_ID = "episode_id";
    public static final String EXTRA_FAVORITE_CHANGED = "favorite_changed";
    public static final String EXTRA_CONTENT_TYPE = "content_type";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EpisodeStore episodeStore;
    private TokenStore tokenStore;
    private YandexAuthService authService;
    private YandexMusicRepository musicRepository;
    private ListView episodeList;
    private TextView episodeSummary;
    private TextView podcastDescriptionView;
    private Button favoriteToggleButton;
    private TextView noEpisodesMessage;
    private View detailStatusPanel;
    private ProgressBar detailProgress;
    private TextView detailMessage;
    private Button detailRetryButton;
    private PlaybackPanelController playbackPanel;
    private long podcastId;
    private String podcastTitle;
    private String podcastDescription;
    private String podcastCoverUri;
    private boolean audiobook;
    private String requestedEpisodeId;
    private boolean podcastFavorite;
    private boolean favoriteChanged;
    private boolean favoriteUpdateInProgress;
    private boolean descriptionExpanded;
    private boolean destroyed;
    private boolean serviceBound;
    private boolean serviceBindingRequested;
    private PlaybackService playbackService;
    private List<Episode> loadedEpisodes = Collections.emptyList();
    private EpisodeListAdapter episodeAdapter;
    private String pendingEpisodeId;

    private final PlaybackService.Listener playbackListener = this::renderPlayback;
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            serviceBound = true;
            playbackService.addListener(playbackListener);
            if (pendingEpisodeId != null) {
                playEpisode(pendingEpisodeId);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playbackService = null;
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeStore.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_podcast_detail);

        podcastId = getIntent().getLongExtra(EXTRA_PODCAST_ID, 0);
        podcastTitle = getIntent().getStringExtra(EXTRA_PODCAST_TITLE);
        podcastDescription = getIntent().getStringExtra(EXTRA_PODCAST_DESCRIPTION);
        podcastCoverUri = getIntent().getStringExtra(EXTRA_PODCAST_COVER_URI);
        audiobook = "audiobook".equals(
                getIntent().getStringExtra(EXTRA_CONTENT_TYPE)
        );
        requestedEpisodeId = getIntent().getStringExtra(EXTRA_EPISODE_ID);
        podcastFavorite = getIntent().getBooleanExtra(EXTRA_PODCAST_FAVORITE, true);
        if (podcastId == 0) {
            finish();
            return;
        }

        JsonHttpClient httpClient = new JsonHttpClient();
        episodeStore = new EpisodeStore(this);
        tokenStore = new TokenStore(this);
        authService = new YandexAuthService(httpClient);
        musicRepository = new YandexMusicRepository(httpClient);

        bindViews();
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        detailRetryButton.setOnClickListener(view -> loadEpisodes());
        TextView title = findViewById(R.id.detailPodcastTitle);
        title.setText(podcastTitle == null
                ? getString(audiobook ? R.string.audiobook_label : R.string.my_podcasts)
                : podcastTitle);
        noEpisodesMessage.setText(audiobook ? R.string.no_chapters : R.string.no_episodes);
        configurePodcastDescription();
        updateFavoriteButton();
        favoriteToggleButton.setOnClickListener(view -> togglePodcastFavorite());

        loadEpisodes();
    }

    private void bindViews() {
        episodeList = findViewById(R.id.episodeList);
        episodeSummary = findViewById(R.id.episodeSummary);
        podcastDescriptionView = findViewById(R.id.detailPodcastDescription);
        favoriteToggleButton = findViewById(R.id.favoriteToggleButton);
        noEpisodesMessage = findViewById(R.id.noEpisodesMessage);
        detailStatusPanel = findViewById(R.id.detailStatusPanel);
        detailProgress = findViewById(R.id.detailProgress);
        detailMessage = findViewById(R.id.detailMessage);
        detailRetryButton = findViewById(R.id.detailRetryButton);
        playbackPanel = new PlaybackPanelController(
                findViewById(R.id.playerPanel),
                new PlaybackPanelController.Listener() {
                    @Override
                    public void onToggle() {
                        if (playbackService != null) playbackService.toggle();
                    }

                    @Override
                    public void onPrevious() {
                        if (playbackService != null) playbackService.previous();
                    }

                    @Override
                    public void onNext() {
                        if (playbackService != null) playbackService.next();
                    }

                    @Override
                    public void onSeekBy(long differenceMs) {
                        if (playbackService != null) playbackService.seekBy(differenceMs);
                    }

                    @Override
                    public void onSeekTo(long positionMs) {
                        if (playbackService != null) playbackService.seekTo(positionMs);
                    }

                    @Override
                    public void onSetSpeed(float speed) {
                        if (playbackService != null) playbackService.setPlaybackSpeed(speed);
                    }

                    @Override
                    public void onOpenCurrentEpisode() {
                        scrollToCurrentEpisode();
                    }
                }
        );
    }

    private void loadEpisodes() {
        showLoading();
        backgroundExecutor.execute(() -> {
            try {
                AuthToken token = tokenStore.load();
                if (token == null) {
                    throw new IllegalStateException("Authorization token is unavailable");
                }
                if (token.isExpired()) {
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                }

                List<Episode> serverEpisodes;
                try {
                    serverEpisodes = musicRepository.loadEpisodes(
                            token.getAccessToken(),
                            podcastId,
                            audiobook
                    );
                } catch (HttpException error) {
                    if (error.getStatusCode() != 401) {
                        throw error;
                    }
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                    serverEpisodes = musicRepository.loadEpisodes(
                            token.getAccessToken(),
                            podcastId,
                            audiobook
                    );
                }
                List<Episode> episodes;
                if (serverEpisodes.isEmpty()) {
                    episodes = episodeStore.getEpisodes(podcastId);
                    if (audiobook && episodes.isEmpty()) {
                        throw new IllegalStateException(
                                "Audiobook chapters are unavailable for this account"
                        );
                    }
                } else {
                    episodes = episodeStore.synchronize(podcastId, serverEpisodes);
                }
                Map<String, EpisodeStore.PlaybackProgress> playbackProgress =
                        episodeStore.getPlaybackProgress(podcastId);
                postUi(() -> {
                    showEpisodes(episodes, playbackProgress);
                    markPodcastSeen();
                });
            } catch (Exception error) {
                List<Episode> cachedEpisodes = episodeStore.getEpisodes(podcastId);
                Map<String, EpisodeStore.PlaybackProgress> playbackProgress =
                        episodeStore.getPlaybackProgress(podcastId);
                postUi(() -> {
                    if (cachedEpisodes.isEmpty()) {
                        showError();
                    } else {
                        showEpisodes(cachedEpisodes, playbackProgress);
                        Toast.makeText(
                                this,
                                R.string.cached_episodes_shown,
                                Toast.LENGTH_LONG
                        ).show();
                        markPodcastSeen();
                    }
                });
            }
        });
    }

    private void showLoading() {
        episodeList.setVisibility(View.GONE);
        noEpisodesMessage.setVisibility(View.GONE);
        detailStatusPanel.setVisibility(View.VISIBLE);
        detailProgress.setVisibility(View.VISIBLE);
        detailMessage.setText(
                audiobook ? R.string.loading_book_chapters : R.string.loading_podcast_episodes
        );
        detailRetryButton.setVisibility(View.GONE);
    }

    private void showError() {
        episodeList.setVisibility(View.GONE);
        noEpisodesMessage.setVisibility(View.GONE);
        detailStatusPanel.setVisibility(View.VISIBLE);
        detailProgress.setVisibility(View.GONE);
        detailMessage.setText(
                audiobook ? R.string.chapters_load_error : R.string.episodes_load_error
        );
        detailRetryButton.setVisibility(View.VISIBLE);
    }

    private void showEpisodes(
            List<Episode> episodes,
            Map<String, EpisodeStore.PlaybackProgress> playbackProgress
    ) {
        loadedEpisodes = new java.util.ArrayList<>(episodes);
        detailStatusPanel.setVisibility(View.GONE);
        episodeSummary.setText(getResources().getQuantityString(
                audiobook ? R.plurals.chapters_count : R.plurals.episodes_count,
                episodes.size(),
                episodes.size()
        ));

        if (episodes.isEmpty()) {
            episodeList.setVisibility(View.GONE);
            noEpisodesMessage.setVisibility(View.VISIBLE);
            return;
        }

        noEpisodesMessage.setVisibility(View.GONE);
        episodeList.setVisibility(View.VISIBLE);
        episodeAdapter = new EpisodeListAdapter(
                this,
                episodes,
                playbackProgress,
                audiobook,
                this::handleEpisodeAction
        );
        episodeList.setAdapter(episodeAdapter);
        episodeList.setOnItemClickListener((parent, view, position, id) -> {
            Episode episode = episodeAdapter.getItem(position);
            if (episode == null) {
                return;
            }
            handleEpisodeAction(episode);
        });

        if (playbackService != null) {
            renderPlayback(playbackService.getSnapshot());
        }

        String episodeToShow = requestedEpisodeId != null
                ? requestedEpisodeId
                : episodeStore.getResumeEpisodeId(podcastId);
        scrollToEpisode(episodeToShow);
    }

    private void handleEpisodeAction(Episode episode) {
        if (episodeAdapter != null) {
            episodeAdapter.setPlaybackState(episode.getId(), false, true, 0, 0);
        }
        if (playbackService != null
                && episode.getId().equals(playbackService.getSnapshot().getEpisodeId())) {
            playbackService.toggle();
            return;
        }
        pendingEpisodeId = episode.getId();
        startService(new Intent(this, PlaybackService.class));
        if (serviceBound) {
            playEpisode(episode.getId());
        }
    }

    private void markPodcastSeen() {
        if (!audiobook) {
            episodeStore.markAllSeen(podcastId);
        }
        updateResult();
    }

    private void configurePodcastDescription() {
        boolean hasDescription = podcastDescription != null
                && !podcastDescription.trim().isEmpty();
        podcastDescriptionView.setText(podcastDescription);
        podcastDescriptionView.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        if (!hasDescription) {
            return;
        }
        podcastDescriptionView.setOnClickListener(view -> {
            descriptionExpanded = !descriptionExpanded;
            podcastDescriptionView.setMaxLines(
                    descriptionExpanded ? Integer.MAX_VALUE : 1
            );
            podcastDescriptionView.setEllipsize(
                    descriptionExpanded ? null : TextUtils.TruncateAt.END
            );
        });
    }

    private void togglePodcastFavorite() {
        if (favoriteUpdateInProgress) {
            return;
        }
        boolean requestedFavorite = !podcastFavorite;
        favoriteUpdateInProgress = true;
        updateFavoriteButton();
        backgroundExecutor.execute(() -> {
            try {
                AuthToken token = tokenStore.load();
                if (token == null) {
                    throw new IllegalStateException("Authorization token is unavailable");
                }
                if (token.isExpired()) {
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                }
                try {
                    if (!musicRepository.setPodcastFavorite(
                            token.getAccessToken(),
                            podcastId,
                            requestedFavorite
                    )) {
                        throw new IllegalStateException("Unexpected favorite response");
                    }
                } catch (HttpException error) {
                    if (error.getStatusCode() != 401) {
                        throw error;
                    }
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                    if (!musicRepository.setPodcastFavorite(
                            token.getAccessToken(),
                            podcastId,
                            requestedFavorite
                    )) {
                        throw new IllegalStateException("Unexpected favorite response");
                    }
                }
                postUi(() -> {
                    podcastFavorite = requestedFavorite;
                    favoriteChanged = true;
                    favoriteUpdateInProgress = false;
                    updateFavoriteButton();
                    updateResult();
                });
            } catch (Exception error) {
                postUi(() -> {
                    favoriteUpdateInProgress = false;
                    updateFavoriteButton();
                    Toast.makeText(
                            this,
                            R.string.favorite_update_error,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void updateFavoriteButton() {
        favoriteToggleButton.setText(
                podcastFavorite
                        ? R.string.remove_from_favorites
                        : R.string.add_to_favorites
        );
        favoriteToggleButton.setBackgroundTintList(ColorStateList.valueOf(getColor(
                podcastFavorite ? R.color.cover_default : R.color.surface
        )));
        favoriteToggleButton.setTextColor(getColor(
                podcastFavorite ? R.color.on_accent : R.color.text_primary
        ));
        favoriteToggleButton.setEnabled(!favoriteUpdateInProgress);
    }

    private void updateResult() {
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_PODCAST_ID, podcastId)
                .putExtra(EXTRA_PODCAST_FAVORITE, podcastFavorite)
                .putExtra(EXTRA_FAVORITE_CHANGED, favoriteChanged)
                .putExtra(EXTRA_CONTENT_TYPE, audiobook ? "audiobook" : "podcast"));
    }

    private void scrollToCurrentEpisode() {
        if (playbackService != null) {
            scrollToEpisode(playbackService.getSnapshot().getEpisodeId());
        }
    }

    private void scrollToEpisode(String episodeId) {
        if (episodeId == null) {
            return;
        }
        if (episodeAdapter == null) {
            requestedEpisodeId = episodeId;
            return;
        }
        int position = episodeAdapter.findPositionByEpisodeId(episodeId);
        if (position >= 0) {
            requestedEpisodeId = null;
            episodeList.post(() -> episodeList.setSelection(position));
        }
    }

    private void playEpisode(String episodeId) {
        if (playbackService == null || loadedEpisodes.isEmpty()) {
            pendingEpisodeId = episodeId;
            return;
        }
        for (int index = 0; index < loadedEpisodes.size(); index++) {
            if (episodeId.equals(loadedEpisodes.get(index).getId())) {
                pendingEpisodeId = null;
                playbackService.playQueue(
                        loadedEpisodes,
                        index,
                        podcastTitle,
                        podcastDescription,
                        audiobook ? "audiobook" : "podcast",
                        podcastCoverUri
                );
                return;
            }
        }
    }

    private void renderPlayback(PlaybackService.Snapshot snapshot) {
        if (destroyed) {
            return;
        }
        playbackPanel.render(snapshot);
        if (episodeAdapter != null) {
            episodeAdapter.setPlaybackState(
                    snapshot.getEpisodeId(),
                    snapshot.isPlaying(),
                    snapshot.isLoading(),
                    snapshot.getPositionMs(),
                    snapshot.getDurationMs()
            );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceBindingRequested = bindService(
                new Intent(this, PlaybackService.class),
                playbackConnection,
                Context.BIND_AUTO_CREATE
        );
    }

    @Override
    protected void onStop() {
        if (serviceBindingRequested) {
            if (playbackService != null) {
                playbackService.removeListener(playbackListener);
            }
            unbindService(playbackConnection);
            serviceBindingRequested = false;
            serviceBound = false;
            playbackService = null;
        }
        super.onStop();
    }

    private void postUi(Runnable action) {
        mainHandler.post(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        backgroundExecutor.shutdownNow();
        if (episodeStore != null) {
            episodeStore.close();
        }
        super.onDestroy();
    }

}
