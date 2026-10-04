package com.example.myapplication;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.auth.AuthToken;
import com.example.myapplication.auth.DeviceCode;
import com.example.myapplication.auth.YandexAuthService;
import com.example.myapplication.data.YandexMusicRepository;
import com.example.myapplication.model.DiscoveryItem;
import com.example.myapplication.model.Episode;
import com.example.myapplication.model.Podcast;
import com.example.myapplication.model.UserProfile;
import com.example.myapplication.network.HttpException;
import com.example.myapplication.network.JsonHttpClient;
import com.example.myapplication.playback.PlaybackService;
import com.example.myapplication.storage.EpisodeStore;
import com.example.myapplication.storage.TokenStore;
import com.example.myapplication.storage.ThemeStore;
import com.example.myapplication.ui.DiscoveryListAdapter;
import com.example.myapplication.ui.PlaybackPanelController;
import com.example.myapplication.ui.PodcastListAdapter;
import com.example.myapplication.ui.RemoteImageLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String LOG_TAG = "MyPodcasts";
    private static final int PODCAST_DETAIL_REQUEST = 1001;
    private static final int TAB_FAVORITES = 0;
    private static final int TAB_DISCOVERY = 1;
    private static final int TAB_BOOKS = 2;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private YandexAuthService authService;
    private YandexMusicRepository musicRepository;
    private TokenStore tokenStore;
    private EpisodeStore episodeStore;

    private View loginContainer;
    private View deviceCodePanel;
    private View contentContainer;
    private View contentStatusPanel;
    private View tabContentFrame;
    private TextView authError;
    private TextView authStatus;
    private TextView deviceCodeText;
    private TextView contentMessage;
    private ProgressBar authProgress;
    private ProgressBar contentProgress;
    private Button loginButton;
    private Button openYandexButton;
    private Button cancelAuthButton;
    private ImageButton copyDeviceCodeButton;
    private Button retryButton;
    private ListView podcastList;
    private ListView discoveryList;
    private View discoverySearchPanel;
    private EditText discoverySearchInput;
    private Button myPodcastsTab;
    private Button discoverTab;
    private Button booksTab;
    private View profileToolbar;
    private View profileHeader;
    private TextView profileAvatarFallback;
    private ImageView profileAvatar;
    private TextView userLogin;
    private ImageButton themeToggleButton;
    private View pullRefreshIndicator;
    private ProgressBar pullRefreshProgress;
    private PlaybackPanelController playbackPanel;

    private DeviceCode pendingDeviceCode;
    private Runnable pollRunnable;
    private Runnable pendingSearchRunnable;
    private Runnable finishTabSwipeRunnable;
    private int authorizationGeneration;
    private boolean destroyed;
    private List<Podcast> displayedPodcasts = Collections.emptyList();
    private List<DiscoveryItem> discoveryHomeItems = Collections.emptyList();
    private List<DiscoveryItem> bookHomeItems = Collections.emptyList();
    private List<DiscoveryItem> displayedDiscoveryItems = Collections.emptyList();
    private PodcastListAdapter podcastAdapter;
    private DiscoveryListAdapter discoveryAdapter;
    private int selectedTab = TAB_FAVORITES;
    private boolean favoritesLoaded;
    private boolean discoveryLoaded;
    private boolean booksLoaded;
    private boolean discoveryShowingSubpage;
    private int discoveryViewGeneration;
    private int discoveryHomeScrollPosition;
    private int discoveryHomeScrollOffset;
    private int bookHomeScrollPosition;
    private int bookHomeScrollOffset;
    private int profileToolbarExpandedHeight;
    private boolean profileToolbarHidden;
    private ValueAnimator profileToolbarAnimator;
    private PlaybackService playbackService;
    private boolean playbackServiceBound;
    private boolean playbackBindingRequested;
    private boolean refreshInProgress;
    private boolean trackingPull;
    private boolean pullGestureActive;
    private boolean contentGestureIntercepted;
    private boolean contentTouchActive;
    private boolean verticalContentGesture;
    private boolean horizontalTabSwipe;
    private Podcast openedPodcast;
    private Podcast pendingCoverPodcast;
    private long coverLoadingPodcastId;
    private int coverPlaybackGeneration;
    private float pullStartY;
    private float tabSwipeStartX;
    private float tabSwipeStartY;

    private final PlaybackService.Listener playbackListener = this::renderMiniPlayer;
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            playbackServiceBound = true;
            playbackService.addListener(playbackListener);
            if (pendingCoverPodcast != null) {
                Podcast podcast = pendingCoverPodcast;
                pendingCoverPodcast = null;
                loadAndPlayFromCover(podcast);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackServiceBound = false;
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
        setContentView(R.layout.activity_main);

        JsonHttpClient httpClient = new JsonHttpClient();
        authService = new YandexAuthService(httpClient);
        musicRepository = new YandexMusicRepository(httpClient);
        tokenStore = new TokenStore(this);
        episodeStore = new EpisodeStore(this);

        bindViews();
        bindActions();
        requestNotificationPermission();
        restoreSession();
    }

    private void bindViews() {
        loginContainer = findViewById(R.id.loginContainer);
        deviceCodePanel = findViewById(R.id.deviceCodePanel);
        contentContainer = findViewById(R.id.contentContainer);
        contentStatusPanel = findViewById(R.id.contentStatusPanel);
        tabContentFrame = findViewById(R.id.tabContentFrame);
        authError = findViewById(R.id.authError);
        authStatus = findViewById(R.id.authStatus);
        deviceCodeText = findViewById(R.id.deviceCodeText);
        contentMessage = findViewById(R.id.contentMessage);
        authProgress = findViewById(R.id.authProgress);
        contentProgress = findViewById(R.id.contentProgress);
        loginButton = findViewById(R.id.loginButton);
        openYandexButton = findViewById(R.id.openYandexButton);
        cancelAuthButton = findViewById(R.id.cancelAuthButton);
        copyDeviceCodeButton = findViewById(R.id.copyDeviceCodeButton);
        retryButton = findViewById(R.id.retryButton);
        podcastList = findViewById(R.id.podcastList);
        discoveryList = findViewById(R.id.discoveryList);
        discoverySearchPanel = findViewById(R.id.discoverySearchPanel);
        discoverySearchInput = findViewById(R.id.discoverySearchInput);
        myPodcastsTab = findViewById(R.id.myPodcastsTab);
        discoverTab = findViewById(R.id.discoverTab);
        booksTab = findViewById(R.id.booksTab);
        profileToolbar = findViewById(R.id.profileToolbar);
        profileHeader = findViewById(R.id.profileHeader);
        profileAvatarFallback = findViewById(R.id.profileAvatarFallback);
        profileAvatar = findViewById(R.id.profileAvatar);
        userLogin = findViewById(R.id.userLogin);
        themeToggleButton = findViewById(R.id.themeToggleButton);
        pullRefreshIndicator = findViewById(R.id.pullRefreshIndicator);
        pullRefreshProgress = findViewById(R.id.pullRefreshProgress);
        playbackPanel = new PlaybackPanelController(
                findViewById(R.id.miniPlayer),
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
                        openCurrentEpisode();
                    }
                }
        );
    }

    private void bindActions() {
        loginButton.setOnClickListener(view -> beginAuthorization());
        openYandexButton.setOnClickListener(view -> openVerificationPage());
        cancelAuthButton.setOnClickListener(view -> cancelAuthorization());
        copyDeviceCodeButton.setOnClickListener(view -> copyDeviceCode());
        retryButton.setOnClickListener(view -> retryCurrentContent());
        myPodcastsTab.setOnClickListener(view -> selectTab(TAB_FAVORITES));
        discoverTab.setOnClickListener(view -> selectTab(TAB_DISCOVERY));
        booksTab.setOnClickListener(view -> selectTab(TAB_BOOKS));
        discoverySearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitPodcastSearch();
                return true;
            }
            return false;
        });
        discoverySearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                scheduleAutomaticSearch(text.toString().trim());
            }
        });
        profileHeader.setOnClickListener(this::showProfileMenu);
        updateThemeButton();
        themeToggleButton.setOnClickListener(view -> {
            ThemeStore.setDark(this, !ThemeStore.isDark(this));
            recreate();
        });
        setupPullToRefresh();
        setupCollapsingProfileToolbar();
    }

    private void restoreSession() {
        cancelAuthorizationPolling();
        AuthToken savedToken = tokenStore.load();
        if (savedToken == null) {
            showLogin(null);
            return;
        }

        showContentLoading();
        if (savedToken.isExpired()) {
            refreshAndLoad(savedToken);
        } else {
            loadPodcasts(savedToken, true);
        }
    }

    private void beginAuthorization() {
        int generation = ++authorizationGeneration;
        cancelAuthorizationPollingOnly();
        authError.setVisibility(View.GONE);
        deviceCodePanel.setVisibility(View.GONE);
        loginButton.setVisibility(View.INVISIBLE);
        authProgress.setVisibility(View.VISIBLE);

        backgroundExecutor.execute(() -> {
            try {
                DeviceCode deviceCode = authService.requestDeviceCode();
                postUi(() -> {
                    if (generation != authorizationGeneration) {
                        return;
                    }
                    pendingDeviceCode = deviceCode;
                    showDeviceCode(deviceCode);
                    scheduleTokenPoll(generation, 0);
                });
            } catch (Exception error) {
                postUi(() -> {
                    if (generation == authorizationGeneration) {
                        showLogin(friendlyMessage(error));
                    }
                });
            }
        });
    }

    private void showDeviceCode(DeviceCode deviceCode) {
        loginButton.setVisibility(View.GONE);
        authProgress.setVisibility(View.GONE);
        deviceCodeText.setText(deviceCode.getUserCode());
        authStatus.setText(R.string.waiting_for_confirmation);
        deviceCodePanel.setVisibility(View.VISIBLE);
    }

    private void scheduleTokenPoll(int generation, long delayMs) {
        cancelAuthorizationPollingOnly();
        pollRunnable = () -> {
            if (generation != authorizationGeneration || pendingDeviceCode == null) {
                return;
            }
            if (pendingDeviceCode.isExpired()) {
                showLogin(getString(R.string.authorization_code_expired));
                return;
            }

            DeviceCode deviceCode = pendingDeviceCode;
            backgroundExecutor.execute(() -> pollToken(deviceCode, generation));
        };
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private void pollToken(DeviceCode deviceCode, int generation) {
        try {
            AuthToken token = authService.pollDeviceToken(deviceCode);
            if (token == null) {
                postUi(() -> {
                    if (generation == authorizationGeneration) {
                        scheduleTokenPoll(
                                generation,
                                deviceCode.getPollIntervalSeconds() * 1_000L
                        );
                    }
                });
                return;
            }

            tokenStore.save(token);
            postUi(() -> {
                if (generation != authorizationGeneration) {
                    return;
                }
                pendingDeviceCode = null;
                authorizationGeneration++;
                showContentLoading();
                loadPodcasts(token, true);
            });
        } catch (HttpException error) {
            if (error.getStatusCode() == 429 || error.getStatusCode() >= 500) {
                retryAuthorizationPoll(deviceCode, generation);
            } else {
                postUi(() -> {
                    if (generation == authorizationGeneration) {
                        showLogin(authErrorMessage(error));
                    }
                });
            }
        } catch (IOException error) {
            retryAuthorizationPoll(deviceCode, generation);
        } catch (Exception error) {
            postUi(() -> {
                if (generation == authorizationGeneration) {
                    showLogin(authErrorMessage(error));
                }
            });
        }
    }

    private void retryAuthorizationPoll(DeviceCode deviceCode, int generation) {
        postUi(() -> {
            if (generation != authorizationGeneration || pendingDeviceCode == null) {
                return;
            }
            if (deviceCode.isExpired()) {
                showLogin(getString(R.string.authorization_code_expired));
                return;
            }
            authStatus.setText(R.string.authorization_retrying);
            scheduleTokenPoll(
                    generation,
                    Math.max(5_000L, deviceCode.getPollIntervalSeconds() * 1_000L)
            );
        });
    }

    private void refreshAndLoad(AuthToken token) {
        backgroundExecutor.execute(() -> {
            try {
                AuthToken refreshedToken = authService.refreshToken(token);
                tokenStore.save(refreshedToken);
                postUi(() -> {
                    if (selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS) {
                        loadDiscovery(refreshedToken, false);
                    } else {
                        loadPodcasts(refreshedToken, false);
                    }
                });
            } catch (Exception error) {
                tokenStore.clear();
                postUi(() -> showLogin(getString(R.string.session_expired)));
            }
        });
    }

    private void loadPodcasts(AuthToken token, boolean allowRefresh) {
        backgroundExecutor.execute(() -> {
            try {
                UserProfile userProfile;
                try {
                    userProfile = musicRepository.loadUserProfile(token.getAccessToken());
                } catch (Exception ignored) {
                    userProfile = new UserProfile("Яндекс", null);
                }
                List<Podcast> favorites = musicRepository.loadFavoritePodcasts(
                        token.getAccessToken()
                );
                List<Podcast> synchronizedPodcasts = synchronizePodcastCounts(favorites);
                UserProfile loadedProfile = userProfile;
                postUi(() -> {
                    renderProfile(loadedProfile);
                    displayPodcasts(synchronizedPodcasts);
                });
            } catch (HttpException error) {
                if (error.getStatusCode() == 401 && allowRefresh) {
                    refreshAndLoad(token);
                } else if (error.getStatusCode() == 401) {
                    tokenStore.clear();
                    postUi(() -> showLogin(getString(R.string.session_expired)));
                } else {
                    postUi(() -> showContentError(friendlyMessage(error)));
                }
            } catch (Exception error) {
                postUi(() -> showContentError(friendlyMessage(error)));
            }
        });
    }

    private List<Podcast> synchronizePodcastCounts(List<Podcast> podcasts) {
        List<Podcast> result = new ArrayList<>();
        for (Podcast podcast : podcasts) {
            int newEpisodeCount = podcast.isAudiobook()
                    ? 0
                    : episodeStore.synchronizePodcastCount(
                            podcast.getId(),
                            podcast.getEpisodeCount()
                    );
            result.add(podcast.withNewEpisodeCount(newEpisodeCount));
        }
        return sortPodcasts(result);
    }

    private List<Podcast> sortPodcasts(List<Podcast> podcasts) {
        List<Podcast> sorted = new ArrayList<>(podcasts);
        Map<Long, Long> lastOpenedTimes = new HashMap<>();
        for (Podcast podcast : sorted) {
            lastOpenedTimes.put(
                    podcast.getId(),
                    episodeStore.getLastPlayedAt(podcast.getId())
            );
        }

        sorted.sort((left, right) -> {
            long leftOpenedAt = lastOpenedTimes.get(left.getId());
            long rightOpenedAt = lastOpenedTimes.get(right.getId());
            int leftGroup = podcastSortGroup(left, leftOpenedAt);
            int rightGroup = podcastSortGroup(right, rightOpenedAt);
            if (leftGroup != rightGroup) {
                return Integer.compare(leftGroup, rightGroup);
            }

            if (leftGroup == 0) {
                int newCountComparison = Integer.compare(
                        right.getNewEpisodeCount(),
                        left.getNewEpisodeCount()
                );
                if (newCountComparison != 0) {
                    return newCountComparison;
                }
            }

            if (leftGroup <= 1) {
                int openedComparison = Long.compare(rightOpenedAt, leftOpenedAt);
                if (openedComparison != 0) {
                    return openedComparison;
                }
            }

            long leftLikedAt = left.getLikedAt() == null
                    ? 0
                    : left.getLikedAt().toEpochMilli();
            long rightLikedAt = right.getLikedAt() == null
                    ? 0
                    : right.getLikedAt().toEpochMilli();
            int likedComparison = Long.compare(rightLikedAt, leftLikedAt);
            if (likedComparison != 0) {
                return likedComparison;
            }
            return left.getTitle().compareToIgnoreCase(right.getTitle());
        });
        return sorted;
    }

    private int podcastSortGroup(Podcast podcast, long lastOpenedAt) {
        if (podcast.getNewEpisodeCount() > 0) {
            return 0;
        }
        return lastOpenedAt > 0 ? 1 : 2;
    }

    private void displayPodcasts(List<Podcast> podcasts) {
        displayedPodcasts = podcasts;
        favoritesLoaded = true;
        finishPullRefresh();
        if (selectedTab != TAB_FAVORITES) {
            return;
        }
        renderFavoritePodcasts();
    }

    private void renderFavoritePodcasts() {
        discoveryList.setVisibility(View.GONE);
        discoverySearchPanel.setVisibility(View.GONE);
        updateTabAppearance();
        List<Podcast> podcasts = displayedPodcasts;
        if (podcasts.isEmpty()) {
            podcastList.setVisibility(View.GONE);
            contentProgress.setVisibility(View.GONE);
            contentMessage.setText(R.string.no_favorite_podcasts);
            retryButton.setVisibility(View.GONE);
            contentStatusPanel.setVisibility(View.VISIBLE);
            return;
        }

        podcastAdapter = new PodcastListAdapter(this, podcasts, this::handleCoverPlayback);
        if (playbackService != null) {
            PlaybackService.Snapshot snapshot = playbackService.getSnapshot();
            updatePodcastPlaybackState(snapshot);
        } else if (coverLoadingPodcastId != 0) {
            podcastAdapter.setPlaybackState(coverLoadingPodcastId, false, true);
        }
        podcastList.setAdapter(podcastAdapter);
        podcastList.setOnItemClickListener((parent, view, position, id) -> {
            Podcast podcast = podcastAdapter.getItem(position);
            openPodcast(podcast, null);
        });
        contentStatusPanel.setVisibility(View.GONE);
        podcastList.setVisibility(View.VISIBLE);
    }

    private void handleCoverPlayback(Podcast podcast) {
        if (playbackService != null) {
            PlaybackService.Snapshot snapshot = playbackService.getSnapshot();
            EpisodeStore.PlaybackProgress currentProgress = episodeStore.getPlaybackProgress(
                    podcast.getId(), snapshot.getEpisodeId()
            );
            if (snapshot.getPodcastId() == podcast.getId()
                    && snapshot.getEpisodeId() != null
                    && (snapshot.isPlaying() || !currentProgress.isCompleted())) {
                coverPlaybackGeneration++;
                pendingCoverPodcast = null;
                playbackService.toggle();
                return;
            }
            loadAndPlayFromCover(podcast);
            return;
        }
        showCoverPlaybackLoading(podcast);
        pendingCoverPodcast = podcast;
        startService(new Intent(this, PlaybackService.class));
    }

    private void loadAndPlayFromCover(Podcast podcast) {
        showCoverPlaybackLoading(podcast);
        int generation = ++coverPlaybackGeneration;
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
                            token.getAccessToken(), podcast.getId(), podcast.isAudiobook()
                    );
                } catch (HttpException error) {
                    if (error.getStatusCode() != 401) {
                        throw error;
                    }
                    token = authService.refreshToken(token);
                    tokenStore.save(token);
                    serverEpisodes = musicRepository.loadEpisodes(
                            token.getAccessToken(), podcast.getId(), podcast.isAudiobook()
                    );
                }
                List<Episode> episodes = serverEpisodes.isEmpty()
                        ? episodeStore.getEpisodes(podcast.getId())
                        : episodeStore.synchronize(podcast.getId(), serverEpisodes);
                playLoadedPodcastFromCover(podcast, episodes, generation);
            } catch (Exception error) {
                List<Episode> cachedEpisodes = episodeStore.getEpisodes(podcast.getId());
                if (cachedEpisodes.isEmpty()) {
                    postUi(() -> {
                        if (generation == coverPlaybackGeneration) {
                            clearCoverPlaybackLoading(podcast.getId());
                            Toast.makeText(
                                    this,
                                    podcast.isAudiobook()
                                            ? R.string.chapters_load_error
                                            : R.string.episodes_load_error,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                } else {
                    playLoadedPodcastFromCover(podcast, cachedEpisodes, generation);
                }
            }
        });
    }

    private void playLoadedPodcastFromCover(
            Podcast podcast,
            List<Episode> episodes,
            int generation
    ) {
        Map<String, EpisodeStore.PlaybackProgress> progress =
                episodeStore.getPlaybackProgress(podcast.getId());
        int episodeIndex = selectCoverEpisode(podcast, episodes, progress);
        postUi(() -> {
            if (generation != coverPlaybackGeneration) {
                return;
            }
            if (episodeIndex < 0) {
                clearCoverPlaybackLoading(podcast.getId());
                Toast.makeText(
                        this,
                        podcast.isAudiobook()
                                ? R.string.chapters_load_error
                                : R.string.episodes_load_error,
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            if (playbackService == null) {
                pendingCoverPodcast = podcast;
                return;
            }
            coverLoadingPodcastId = 0;
            playbackService.playQueue(
                    episodes,
                    episodeIndex,
                    podcast.getTitle(),
                    podcast.getDescription(),
                    podcast.isAudiobook() ? "audiobook" : "podcast",
                    podcast.getCoverUri()
            );
        });
    }

    private void showCoverPlaybackLoading(Podcast podcast) {
        coverLoadingPodcastId = podcast.getId();
        if (podcastAdapter != null) {
            podcastAdapter.setPlaybackState(podcast.getId(), false, true);
        }
    }

    private void clearCoverPlaybackLoading(long podcastId) {
        if (coverLoadingPodcastId != podcastId) {
            return;
        }
        coverLoadingPodcastId = 0;
        if (podcastAdapter == null) {
            return;
        }
        if (playbackService == null) {
            podcastAdapter.setPlaybackState(0, false, false);
        } else {
            updatePodcastPlaybackState(playbackService.getSnapshot());
        }
    }

    private void updatePodcastPlaybackState(PlaybackService.Snapshot snapshot) {
        if (podcastAdapter == null) {
            return;
        }
        if (coverLoadingPodcastId != 0) {
            podcastAdapter.setPlaybackState(coverLoadingPodcastId, false, true);
            return;
        }
        podcastAdapter.setPlaybackState(
                snapshot.getPodcastId(),
                snapshot.isPlaying(),
                snapshot.isLoading()
        );
    }

    private int selectCoverEpisode(
            Podcast podcast,
            List<Episode> episodes,
            Map<String, EpisodeStore.PlaybackProgress> progress
    ) {
        if (episodes.isEmpty()) {
            return -1;
        }
        String lastEpisodeId = episodeStore.getLastOpenedEpisodeId(podcast.getId());
        if (lastEpisodeId == null) {
            lastEpisodeId = episodeStore.getResumeEpisodeId(podcast.getId());
        }
        int lastIndex = -1;
        for (int index = 0; index < episodes.size(); index++) {
            if (episodes.get(index).getId().equals(lastEpisodeId)) {
                lastIndex = index;
                break;
            }
        }
        if (lastIndex >= 0 && !isCompleted(episodes.get(lastIndex), progress)) {
            return lastIndex;
        }
        if (lastIndex >= 0) {
            for (int index = lastIndex + 1; index < episodes.size(); index++) {
                if (!isCompleted(episodes.get(index), progress)) {
                    return index;
                }
            }
        }
        if (podcast.isAudiobook()) {
            for (int index = 0; index < episodes.size(); index++) {
                if (!isCompleted(episodes.get(index), progress)) {
                    return index;
                }
            }
        } else {
            for (int index = episodes.size() - 1; index >= 0; index--) {
                if (!isCompleted(episodes.get(index), progress)) {
                    return index;
                }
            }
        }
        return episodes.size() - 1;
    }

    private boolean isCompleted(
            Episode episode,
            Map<String, EpisodeStore.PlaybackProgress> progress
    ) {
        EpisodeStore.PlaybackProgress savedProgress = progress.get(episode.getId());
        return episode.isListened()
                || (savedProgress != null && savedProgress.isCompleted());
    }

    private void displayDiscovery(
            List<DiscoveryItem> items,
            boolean rememberAsHome,
            int targetTab
    ) {
        if (rememberAsHome) {
            if (targetTab == TAB_BOOKS) {
                bookHomeItems = items;
                booksLoaded = true;
            } else {
                discoveryHomeItems = items;
                discoveryLoaded = true;
            }
        }
        finishPullRefresh();
        if (selectedTab != targetTab) {
            return;
        }
        displayedDiscoveryItems = items;
        if (rememberAsHome) {
            discoveryShowingSubpage = false;
        }
        renderDiscovery();
    }

    private void renderDiscovery() {
        podcastList.setVisibility(View.GONE);
        discoverySearchInput.setHint(selectedTab == TAB_BOOKS
                ? R.string.search_books_hint
                : R.string.search_podcasts_hint);
        discoverySearchPanel.setVisibility(
                selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS
                        ? View.VISIBLE
                        : View.GONE
        );
        updateTabAppearance();
        if (displayedDiscoveryItems.isEmpty()) {
            discoveryList.setVisibility(View.GONE);
            contentProgress.setVisibility(View.GONE);
            if (selectedTab == TAB_BOOKS) {
                contentMessage.setText(discoverySearchInput.getText().toString().trim().isEmpty()
                        ? R.string.no_discovery_books
                        : R.string.no_book_search_results);
            } else {
                contentMessage.setText(discoverySearchInput.getText().toString().trim().isEmpty()
                        ? R.string.no_discovery_podcasts
                        : R.string.no_search_results);
            }
            retryButton.setVisibility(View.GONE);
            contentStatusPanel.setVisibility(View.VISIBLE);
            return;
        }

        discoveryAdapter = new DiscoveryListAdapter(this, displayedDiscoveryItems);
        discoveryList.setAdapter(discoveryAdapter);
        discoveryList.setOnItemClickListener((parent, view, position, id) -> {
            DiscoveryItem item = discoveryAdapter.getItem(position);
            if (item.getType() == DiscoveryItem.TYPE_PODCAST) {
                openPodcast(item.getPodcast(), item.getEpisodeId());
            } else if (item.getType() == DiscoveryItem.TYPE_CATEGORY) {
                loadCategory(item);
            }
        });
        contentStatusPanel.setVisibility(View.GONE);
        discoveryList.setVisibility(View.VISIBLE);
    }

    private void openPodcast(Podcast podcast, String episodeId) {
        openedPodcast = podcast;
        Intent intent = new Intent(this, PodcastDetailActivity.class);
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_ID, podcast.getId());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_TITLE, podcast.getTitle());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_DESCRIPTION, podcast.getDescription());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_COVER_URI, podcast.getCoverUri());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_FAVORITE, isFavorite(podcast.getId()));
        intent.putExtra(
                PodcastDetailActivity.EXTRA_CONTENT_TYPE,
                podcast.isAudiobook() ? "audiobook" : "podcast"
        );
        if (episodeId != null) {
            intent.putExtra(PodcastDetailActivity.EXTRA_EPISODE_ID, episodeId);
        }
        startActivityForResult(intent, PODCAST_DETAIL_REQUEST);
    }

    private boolean isFavorite(long podcastId) {
        for (Podcast podcast : displayedPodcasts) {
            if (podcast.getId() == podcastId) {
                return true;
            }
        }
        return false;
    }

    private void selectTab(int tab) {
        if (selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS) {
            saveDiscoveryHomeScrollPosition();
        }
        selectedTab = tab;
        finishPullRefresh();
        if (tab == TAB_FAVORITES) {
            if (favoritesLoaded) {
                renderFavoritePodcasts();
            } else {
                retryCurrentContent();
            }
            return;
        }

        discoverySearchInput.setText("");
        discoveryViewGeneration++;
        discoveryShowingSubpage = false;
        boolean loaded = tab == TAB_BOOKS ? booksLoaded : discoveryLoaded;
        if (loaded) {
            displayedDiscoveryItems = tab == TAB_BOOKS
                    ? bookHomeItems
                    : discoveryHomeItems;
            renderDiscovery();
            restoreDiscoveryHomeScrollPosition();
        } else {
            retryCurrentContent();
        }
    }

    private void updateTabAppearance() {
        int activeBackground = getColor(R.color.cover_default);
        int inactiveBackground = getColor(R.color.surface);
        int activeText = android.graphics.Color.rgb(23, 23, 26);
        int inactiveText = getColor(R.color.text_primary);
        Button[] tabs = {myPodcastsTab, discoverTab, booksTab};
        for (int index = 0; index < tabs.length; index++) {
            boolean selected = selectedTab == index;
            tabs[index].setBackgroundTintList(ColorStateList.valueOf(
                    selected ? activeBackground : inactiveBackground
            ));
            tabs[index].setTextColor(selected ? activeText : inactiveText);
        }
    }

    private void retryCurrentContent() {
        AuthToken token = tokenStore.load();
        if (token == null) {
            restoreSession();
            return;
        }
        int loadingMessage = selectedTab == TAB_BOOKS
                ? R.string.loading_books
                : selectedTab == TAB_DISCOVERY
                        ? R.string.loading_discovery
                        : R.string.loading_podcasts;
        if (token.isExpired()) {
            showSelectedLoading(loadingMessage);
            refreshAndLoad(token);
            return;
        }
        showSelectedLoading(loadingMessage);
        if (selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS) {
            loadDiscovery(token, true);
        } else {
            loadPodcasts(token, true);
        }
    }

    private void loadDiscovery(AuthToken token, boolean allowRefresh) {
        int targetTab = selectedTab;
        boolean books = targetTab == TAB_BOOKS;
        backgroundExecutor.execute(() -> {
            try {
                List<DiscoveryItem> items = books
                        ? musicRepository.loadBookDiscovery(token.getAccessToken())
                        : musicRepository.loadPodcastDiscovery(token.getAccessToken());
                postUi(() -> displayDiscovery(items, true, targetTab));
            } catch (HttpException error) {
                if (error.getStatusCode() == 401 && allowRefresh) {
                    refreshAndLoad(token);
                } else if (error.getStatusCode() == 401) {
                    tokenStore.clear();
                    postUi(() -> showLogin(getString(R.string.session_expired)));
                } else {
                    postUi(() -> showContentError(getString(
                            books
                                    ? R.string.books_load_error
                                    : R.string.discovery_load_error
                    )));
                }
            } catch (Exception error) {
                postUi(() -> showContentError(getString(
                        books ? R.string.books_load_error : R.string.discovery_load_error
                )));
            }
        });
    }

    private void submitPodcastSearch() {
        cancelPendingSearch();
        String query = discoverySearchInput.getText().toString().trim();
        if (query.isEmpty()) {
            showDiscoveryHome();
            return;
        }
        AuthToken token = tokenStore.load();
        if (token == null) {
            showLogin(null);
            return;
        }
        if (!discoveryShowingSubpage) {
            saveDiscoveryHomeScrollPosition();
        }
        int generation = ++discoveryViewGeneration;
        int targetTab = selectedTab;
        boolean books = targetTab == TAB_BOOKS;
        discoveryShowingSubpage = true;
        showSelectedLoading(books ? R.string.loading_book_search : R.string.loading_search);
        backgroundExecutor.execute(() -> {
            try {
                List<DiscoveryItem> items = books
                        ? musicRepository.searchBooks(token.getAccessToken(), query, 0)
                        : musicRepository.searchPodcasts(token.getAccessToken(), query, 0);
                postUi(() -> {
                    if (generation == discoveryViewGeneration) {
                        displayDiscovery(
                                withSection("Результаты поиска", items),
                                false,
                                targetTab
                        );
                    }
                });
            } catch (Exception error) {
                postUi(() -> {
                    if (generation == discoveryViewGeneration) {
                        showContentError(getString(R.string.search_load_error));
                    }
                });
            }
        });
    }

    private void scheduleAutomaticSearch(String query) {
        cancelPendingSearch();
        discoveryViewGeneration++;
        if (query.isEmpty()) {
            showDiscoveryHome();
            return;
        }
        if (query.length() < 3
                || (selectedTab != TAB_DISCOVERY && selectedTab != TAB_BOOKS)) {
            return;
        }
        int targetTab = selectedTab;
        pendingSearchRunnable = () -> {
            pendingSearchRunnable = null;
            String currentQuery = discoverySearchInput.getText().toString().trim();
            if (selectedTab == targetTab && currentQuery.equals(query)) {
                submitPodcastSearch();
            }
        };
        mainHandler.postDelayed(pendingSearchRunnable, 350);
    }

    private void cancelPendingSearch() {
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
    }

    private void showDiscoveryHome() {
        discoveryViewGeneration++;
        discoveryShowingSubpage = false;
        displayedDiscoveryItems = selectedTab == TAB_BOOKS
                ? bookHomeItems
                : discoveryHomeItems;
        renderDiscovery();
        restoreDiscoveryHomeScrollPosition();
    }

    private void loadCategory(DiscoveryItem category) {
        AuthToken token = tokenStore.load();
        if (token == null) {
            showLogin(null);
            return;
        }
        saveDiscoveryHomeScrollPosition();
        int generation = ++discoveryViewGeneration;
        int targetTab = selectedTab;
        boolean books = targetTab == TAB_BOOKS;
        discoveryShowingSubpage = true;
        showSelectedLoading(books ? R.string.loading_books : R.string.loading_discovery);
        backgroundExecutor.execute(() -> {
            try {
                List<DiscoveryItem> items = books
                        ? musicRepository.loadBookCategory(
                                token.getAccessToken(),
                                category.getCategoryPath(),
                                category.getTitle()
                        )
                        : musicRepository.loadPodcastCategory(
                                token.getAccessToken(),
                                category.getCategoryPath(),
                                category.getTitle()
                        );
                postUi(() -> {
                    if (generation == discoveryViewGeneration) {
                        displayDiscovery(
                                withSection(category.getTitle(), items),
                                false,
                                targetTab
                        );
                    }
                });
            } catch (Exception error) {
                postUi(() -> {
                    if (generation == discoveryViewGeneration) {
                        showContentError(getString(R.string.discovery_load_error));
                    }
                });
            }
        });
    }

    private List<DiscoveryItem> withSection(String title, List<DiscoveryItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        List<DiscoveryItem> result = new ArrayList<>();
        result.add(DiscoveryItem.section(title));
        result.addAll(items);
        return result;
    }

    private void showSelectedLoading(int messageId) {
        loginContainer.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);
        podcastList.setVisibility(View.GONE);
        discoveryList.setVisibility(View.GONE);
        discoverySearchPanel.setVisibility(
                selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS
                        ? View.VISIBLE
                        : View.GONE
        );
        contentStatusPanel.setVisibility(View.VISIBLE);
        contentProgress.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        contentMessage.setText(messageId);
        updateTabAppearance();
    }

    private void saveDiscoveryHomeScrollPosition() {
        if (discoveryShowingSubpage || discoveryList.getVisibility() != View.VISIBLE) {
            return;
        }
        int position = discoveryList.getFirstVisiblePosition();
        View firstVisibleItem = discoveryList.getChildAt(0);
        int offset = firstVisibleItem == null ? 0 : firstVisibleItem.getTop();
        if (selectedTab == TAB_BOOKS) {
            bookHomeScrollPosition = position;
            bookHomeScrollOffset = offset;
        } else {
            discoveryHomeScrollPosition = position;
            discoveryHomeScrollOffset = offset;
        }
    }

    private void restoreDiscoveryHomeScrollPosition() {
        int position = selectedTab == TAB_BOOKS
                ? bookHomeScrollPosition
                : discoveryHomeScrollPosition;
        int offset = selectedTab == TAB_BOOKS
                ? bookHomeScrollOffset
                : discoveryHomeScrollOffset;
        discoveryList.post(() -> discoveryList.setSelectionFromTop(position, offset));
    }

    @Override
    public void onBackPressed() {
        boolean currentHomeLoaded = selectedTab == TAB_BOOKS ? booksLoaded : discoveryLoaded;
        if ((selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS)
                && discoveryShowingSubpage
                && currentHomeLoaded) {
            discoveryViewGeneration++;
            discoveryShowingSubpage = false;
            discoverySearchInput.setText("");
            displayedDiscoveryItems = selectedTab == TAB_BOOKS
                    ? bookHomeItems
                    : discoveryHomeItems;
            renderDiscovery();
            restoreDiscoveryHomeScrollPosition();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PODCAST_DETAIL_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }

        if (data.getBooleanExtra(PodcastDetailActivity.EXTRA_FAVORITE_CHANGED, false)) {
            applyFavoriteChange(data);
            return;
        }

        openedPodcast = null;
        long podcastId = data.getLongExtra(PodcastDetailActivity.EXTRA_PODCAST_ID, 0);
        for (Podcast podcast : displayedPodcasts) {
            if (podcast.getId() == podcastId) {
                podcast.markAllEpisodesSeen();
                break;
            }
        }
        displayPodcasts(sortPodcasts(displayedPodcasts));
    }

    private void applyFavoriteChange(Intent data) {
        long podcastId = data.getLongExtra(PodcastDetailActivity.EXTRA_PODCAST_ID, 0);
        boolean favorite = data.getBooleanExtra(
                PodcastDetailActivity.EXTRA_PODCAST_FAVORITE,
                false
        );
        List<Podcast> updated = new ArrayList<>();
        for (Podcast podcast : displayedPodcasts) {
            if (podcast.getId() != podcastId) {
                updated.add(podcast);
            }
        }

        Podcast changedPodcast = openedPodcast;
        openedPodcast = null;
        if (favorite && changedPodcast != null && changedPodcast.getId() == podcastId) {
            updated.add(changedPodcast);
            displayPodcasts(sortPodcasts(updated));
            return;
        }
        if (!favorite) {
            displayPodcasts(sortPodcasts(updated));
            return;
        }

        AuthToken token = tokenStore.load();
        if (token != null) {
            loadPodcasts(token, true);
        }
    }

    private void openCurrentEpisode() {
        if (playbackService == null) {
            return;
        }
        PlaybackService.Snapshot snapshot = playbackService.getSnapshot();
        if (snapshot.getPodcastId() == 0 || snapshot.getEpisodeId() == null) {
            return;
        }

        Podcast podcast = null;
        for (Podcast candidate : displayedPodcasts) {
            if (candidate.getId() == snapshot.getPodcastId()) {
                podcast = candidate;
                break;
            }
        }

        openedPodcast = podcast;

        Intent intent = new Intent(this, PodcastDetailActivity.class);
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_ID, snapshot.getPodcastId());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_TITLE, snapshot.getPodcastTitle());
        intent.putExtra(PodcastDetailActivity.EXTRA_EPISODE_ID, snapshot.getEpisodeId());
        intent.putExtra(PodcastDetailActivity.EXTRA_PODCAST_FAVORITE, podcast != null);
        intent.putExtra(
                PodcastDetailActivity.EXTRA_CONTENT_TYPE,
                snapshot.getContentType()
        );
        intent.putExtra(
                PodcastDetailActivity.EXTRA_PODCAST_DESCRIPTION,
                podcast == null
                        ? snapshot.getPodcastDescription()
                        : podcast.getDescription()
        );
        intent.putExtra(
                PodcastDetailActivity.EXTRA_PODCAST_COVER_URI,
                podcast == null ? snapshot.getArtworkUri() : podcast.getCoverUri()
        );
        startActivityForResult(intent, PODCAST_DETAIL_REQUEST);
    }

    private void openVerificationPage() {
        if (pendingDeviceCode != null) {
            openUrl(pendingDeviceCode.getVerificationUrl());
        }
    }

    private void copyDeviceCode() {
        CharSequence code = deviceCodeText.getText();
        if (code == null || code.length() == 0) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.your_authorization_code),
                code
        ));
        Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            if (loginContainer.getVisibility() == View.VISIBLE) {
                authError.setText(R.string.browser_not_found);
                authError.setVisibility(View.VISIBLE);
            } else {
                showContentError(getString(R.string.browser_not_found));
            }
        }
    }

    private void cancelAuthorization() {
        authorizationGeneration++;
        cancelAuthorizationPolling();
        showLogin(null);
    }

    private void logout() {
        startService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_STOP));
        tokenStore.clear();
        episodeStore.clearAll();
        authorizationGeneration++;
        cancelAuthorizationPolling();
        displayedPodcasts = Collections.emptyList();
        displayedDiscoveryItems = Collections.emptyList();
        discoveryHomeItems = Collections.emptyList();
        bookHomeItems = Collections.emptyList();
        favoritesLoaded = false;
        discoveryLoaded = false;
        booksLoaded = false;
        selectedTab = TAB_FAVORITES;
        podcastAdapter = null;
        discoveryAdapter = null;
        podcastList.setAdapter(null);
        discoveryList.setAdapter(null);
        showLogin(null);
    }

    private void renderProfile(UserProfile profile) {
        String login = profile.getLogin();
        userLogin.setText(login);
        profileAvatarFallback.setText(login.substring(0, 1).toUpperCase());
        RemoteImageLoader.load(
                profileAvatar,
                profileAvatarFallback,
                profile.getAvatarUrl()
        );
    }

    private void showProfileMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.logout);
        menu.setOnMenuItemClickListener(item -> {
            logout();
            return true;
        });
        menu.show();
    }

    private void updateThemeButton() {
        boolean dark = ThemeStore.isDark(this);
        themeToggleButton.setImageResource(
                dark ? R.drawable.ic_theme_sun : R.drawable.ic_theme_moon
        );
        themeToggleButton.setContentDescription(getString(
                dark ? R.string.light_theme : R.string.dark_theme
        ));
    }

    private void setupPullToRefresh() {
        final float threshold = 96 * getResources().getDisplayMetrics().density;
        final float swipeThreshold = 72 * getResources().getDisplayMetrics().density;
        final float touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        View.OnTouchListener listener = (view, event) -> {
            if (refreshInProgress) {
                return false;
            }
            ListView activeList = selectedTab == TAB_FAVORITES
                    ? podcastList
                    : discoveryList;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (finishTabSwipeRunnable != null) {
                        mainHandler.removeCallbacks(finishTabSwipeRunnable);
                        finishTabSwipeRunnable = null;
                    }
                    contentTouchActive = true;
                    verticalContentGesture = false;
                    horizontalTabSwipe = false;
                    trackingPull = !activeList.canScrollVertically(-1);
                    pullGestureActive = false;
                    contentGestureIntercepted = false;
                    pullStartY = event.getRawY();
                    tabSwipeStartX = event.getRawX();
                    tabSwipeStartY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float horizontalDistance = event.getRawX() - tabSwipeStartX;
                    float verticalDistance = event.getRawY() - tabSwipeStartY;
                    if (!horizontalTabSwipe
                            && !verticalContentGesture
                            && Math.max(
                                    Math.abs(horizontalDistance),
                                    Math.abs(verticalDistance)
                            ) >= touchSlop) {
                        if (Math.abs(horizontalDistance) > Math.abs(verticalDistance)) {
                            horizontalTabSwipe = true;
                        } else {
                            verticalContentGesture = true;
                        }
                    }
                    if (horizontalTabSwipe) {
                        if (!contentGestureIntercepted) {
                            cancelListTouch(activeList, event);
                            contentGestureIntercepted = true;
                        }
                        trackingPull = false;
                        pullRefreshIndicator.setVisibility(View.GONE);
                        pullRefreshProgress.setVisibility(View.GONE);
                        return true;
                    }
                    if (verticalContentGesture && verticalDistance < -touchSlop) {
                        trackingPull = false;
                    }
                    if (trackingPull && verticalContentGesture) {
                        float distance = event.getRawY() - pullStartY;
                        if (distance > touchSlop) {
                            if (!contentGestureIntercepted) {
                                cancelListTouch(activeList, event);
                                contentGestureIntercepted = true;
                            }
                            pullGestureActive = true;
                            setProfileToolbarHidden(false);
                            pullRefreshIndicator.setVisibility(View.VISIBLE);
                            pullRefreshProgress.setVisibility(View.VISIBLE);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float swipeX = event.getRawX() - tabSwipeStartX;
                    boolean horizontalSwipe = event.getActionMasked() == MotionEvent.ACTION_UP
                            && horizontalTabSwipe
                            && Math.abs(swipeX) >= swipeThreshold;
                    if (horizontalSwipe) {
                        switchTabBySwipe(swipeX < 0 ? 1 : -1);
                        pullRefreshIndicator.setVisibility(View.GONE);
                        pullRefreshProgress.setVisibility(View.GONE);
                        trackingPull = false;
                        pullGestureActive = false;
                        contentGestureIntercepted = false;
                        contentTouchActive = false;
                        verticalContentGesture = false;
                        finishTabSwipeRunnable = () -> {
                            horizontalTabSwipe = false;
                            finishTabSwipeRunnable = null;
                        };
                        mainHandler.postDelayed(finishTabSwipeRunnable, 250);
                        return true;
                    } else if (trackingPull
                            && pullGestureActive
                            && event.getRawY() - pullStartY >= threshold) {
                        beginPullRefresh();
                    } else {
                        pullRefreshIndicator.setVisibility(View.GONE);
                        pullRefreshProgress.setVisibility(View.GONE);
                    }
                    boolean consumeGesture = pullGestureActive || horizontalTabSwipe;
                    trackingPull = false;
                    pullGestureActive = false;
                    contentGestureIntercepted = false;
                    contentTouchActive = false;
                    verticalContentGesture = false;
                    horizontalTabSwipe = false;
                    if (consumeGesture) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        };
        podcastList.setOnTouchListener(listener);
        discoveryList.setOnTouchListener(listener);
        contentStatusPanel.setOnTouchListener(listener);
    }

    private void cancelListTouch(ListView listView, MotionEvent sourceEvent) {
        MotionEvent cancelEvent = MotionEvent.obtain(sourceEvent);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        listView.onTouchEvent(cancelEvent);
        cancelEvent.recycle();
        listView.setPressed(false);
    }

    private void switchTabBySwipe(int direction) {
        int targetTab = selectedTab + direction;
        if (targetTab < TAB_FAVORITES || targetTab > TAB_BOOKS) {
            return;
        }
        selectTab(targetTab);
        float offset = getResources().getDisplayMetrics().widthPixels * 0.18f;
        float startTranslation = direction > 0 ? offset : -offset;
        animateTabView(tabContentFrame, startTranslation);
        if (discoverySearchPanel.getVisibility() == View.VISIBLE) {
            animateTabView(discoverySearchPanel, startTranslation);
        }
    }

    private void animateTabView(View view, float startTranslation) {
        view.animate().cancel();
        view.setTranslationX(startTranslation);
        view.setAlpha(0.72f);
        view.animate()
                .translationX(0)
                .alpha(1f)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void setupCollapsingProfileToolbar() {
        profileToolbar.post(() -> profileToolbarExpandedHeight = profileToolbar.getHeight());
        attachProfileToolbarScrollListener(podcastList);
        attachProfileToolbarScrollListener(discoveryList);
    }

    private void attachProfileToolbarScrollListener(ListView listView) {
        final int movementThreshold = Math.round(
                4 * getResources().getDisplayMetrics().density
        );
        final int[] previousFirstPosition = {0};
        final int[] previousFirstTop = {0};
        final boolean[] initialized = {false};

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // Direction is handled in onScroll so the animation follows the gesture.
            }

            @Override
            public void onScroll(
                    AbsListView view,
                    int firstVisibleItem,
                    int visibleItemCount,
                    int totalItemCount
            ) {
                if (visibleItemCount == 0 || view.getChildAt(0) == null) {
                    return;
                }
                int firstTop = view.getChildAt(0).getTop();
                if (!initialized[0]) {
                    initialized[0] = true;
                    previousFirstPosition[0] = firstVisibleItem;
                    previousFirstTop[0] = firstTop;
                    return;
                }
                if (horizontalTabSwipe || (contentTouchActive && !verticalContentGesture)) {
                    previousFirstPosition[0] = firstVisibleItem;
                    previousFirstTop[0] = firstTop;
                    return;
                }
                if ((trackingPull && pullGestureActive) || refreshInProgress) {
                    setProfileToolbarHidden(false);
                    previousFirstPosition[0] = firstVisibleItem;
                    previousFirstTop[0] = firstTop;
                    return;
                }
                if (profileToolbarAnimator != null && profileToolbarAnimator.isRunning()) {
                    previousFirstPosition[0] = firstVisibleItem;
                    previousFirstTop[0] = firstTop;
                    return;
                }

                if (firstVisibleItem == 0 && firstTop >= view.getPaddingTop()) {
                    setProfileToolbarHidden(false);
                } else if (firstVisibleItem > previousFirstPosition[0]
                        || (firstVisibleItem == previousFirstPosition[0]
                        && firstTop < previousFirstTop[0] - movementThreshold)) {
                    setProfileToolbarHidden(true);
                } else if (firstVisibleItem < previousFirstPosition[0]
                        || (firstVisibleItem == previousFirstPosition[0]
                        && firstTop > previousFirstTop[0] + movementThreshold)) {
                    setProfileToolbarHidden(false);
                }

                previousFirstPosition[0] = firstVisibleItem;
                previousFirstTop[0] = firstTop;
            }
        });
    }

    private void setProfileToolbarHidden(boolean hidden) {
        if (profileToolbarHidden == hidden) {
            return;
        }
        if (profileToolbarExpandedHeight == 0) {
            profileToolbarExpandedHeight = profileToolbar.getHeight();
        }
        if (profileToolbarExpandedHeight == 0) {
            return;
        }

        profileToolbarHidden = hidden;
        if (profileToolbarAnimator != null) {
            profileToolbarAnimator.cancel();
        }
        int startHeight = profileToolbar.getHeight();
        int endHeight = hidden ? 0 : profileToolbarExpandedHeight;
        profileToolbarAnimator = ValueAnimator.ofInt(startHeight, endHeight);
        profileToolbarAnimator.setDuration(220);
        profileToolbarAnimator.setInterpolator(new DecelerateInterpolator());
        profileToolbarAnimator.addUpdateListener(animation -> {
            int height = (int) animation.getAnimatedValue();
            ViewGroup.LayoutParams layoutParams = profileToolbar.getLayoutParams();
            layoutParams.height = height;
            profileToolbar.setLayoutParams(layoutParams);
            profileToolbar.setAlpha(
                    Math.min(1f, height / (float) profileToolbarExpandedHeight)
            );
        });
        profileToolbarAnimator.start();
    }

    private void beginPullRefresh() {
        AuthToken token = tokenStore.load();
        if (token == null) {
            showLogin(null);
            return;
        }
        refreshInProgress = true;
        setProfileToolbarHidden(false);
        pullRefreshIndicator.setVisibility(View.VISIBLE);
        pullRefreshProgress.setVisibility(View.VISIBLE);
        if (token.isExpired()) {
            refreshAndLoad(token);
        } else if (selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS) {
            loadDiscovery(token, true);
        } else {
            loadPodcasts(token, true);
        }
    }

    private void finishPullRefresh() {
        refreshInProgress = false;
        pullRefreshIndicator.setVisibility(View.GONE);
        pullRefreshProgress.setVisibility(View.GONE);
    }

    private void renderMiniPlayer(PlaybackService.Snapshot snapshot) {
        if (destroyed) {
            return;
        }
        updatePodcastPlaybackState(snapshot);
        playbackPanel.render(snapshot);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        playbackBindingRequested = bindService(
                new Intent(this, PlaybackService.class),
                playbackConnection,
                Context.BIND_AUTO_CREATE
        );
    }

    @Override
    protected void onStop() {
        if (playbackBindingRequested) {
            if (playbackService != null) {
                playbackService.removeListener(playbackListener);
            }
            unbindService(playbackConnection);
            playbackBindingRequested = false;
            playbackServiceBound = false;
            playbackService = null;
        }
        super.onStop();
    }

    private void cancelAuthorizationPolling() {
        cancelAuthorizationPollingOnly();
        pendingDeviceCode = null;
    }

    private void cancelAuthorizationPollingOnly() {
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void showLogin(String errorMessage) {
        finishPullRefresh();
        loginContainer.setVisibility(View.VISIBLE);
        contentContainer.setVisibility(View.GONE);
        deviceCodePanel.setVisibility(View.GONE);
        authProgress.setVisibility(View.GONE);
        loginButton.setVisibility(View.VISIBLE);

        if (errorMessage == null || errorMessage.isEmpty()) {
            authError.setVisibility(View.GONE);
        } else {
            authError.setText(errorMessage);
            authError.setVisibility(View.VISIBLE);
        }
    }

    private void showContentLoading() {
        finishPullRefresh();
        loginContainer.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);
        podcastList.setVisibility(View.GONE);
        discoveryList.setVisibility(View.GONE);
        discoverySearchPanel.setVisibility(
                selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS
                        ? View.VISIBLE
                        : View.GONE
        );
        contentStatusPanel.setVisibility(View.VISIBLE);
        contentProgress.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        contentMessage.setText(selectedTab == TAB_BOOKS
                ? R.string.loading_books
                : selectedTab == TAB_DISCOVERY
                        ? R.string.loading_discovery
                        : R.string.loading_podcasts);
        updateTabAppearance();
    }

    private void showContentError(String message) {
        boolean hasVisibleContent = selectedTab == TAB_FAVORITES
                ? !displayedPodcasts.isEmpty()
                : !displayedDiscoveryItems.isEmpty();
        if (refreshInProgress && hasVisibleContent) {
            finishPullRefresh();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        finishPullRefresh();
        loginContainer.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);
        podcastList.setVisibility(View.GONE);
        discoveryList.setVisibility(View.GONE);
        discoverySearchPanel.setVisibility(
                selectedTab == TAB_DISCOVERY || selectedTab == TAB_BOOKS
                        ? View.VISIBLE
                        : View.GONE
        );
        contentStatusPanel.setVisibility(View.VISIBLE);
        contentProgress.setVisibility(View.GONE);
        contentMessage.setText(message);
        retryButton.setVisibility(View.VISIBLE);
        updateTabAppearance();
    }

    private String authErrorMessage(Exception error) {
        if (error instanceof HttpException) {
            String errorCode = ((HttpException) error).getErrorCode();
            if ("authorization_declined".equals(errorCode)
                    || "access_denied".equals(errorCode)) {
                return getString(R.string.authorization_failed);
            }
            if ("expired_token".equals(errorCode) || "bad_verification_code".equals(errorCode)) {
                return getString(R.string.authorization_code_expired);
            }
            if ("invalid_grant".equals(errorCode)) {
                return getString(R.string.authorization_code_expired);
            }
        }
        return friendlyMessage(error);
    }

    private String friendlyMessage(Throwable error) {
        Log.e(
                LOG_TAG,
                "Request failed: " + error.getClass().getName() + ": " + error.getMessage(),
                error
        );
        if (error instanceof HttpException) {
            HttpException httpError = (HttpException) error;
            if (httpError.getStatusCode() == 403) {
                return getString(R.string.yandex_access_error);
            }
            if (httpError.getStatusCode() == 429) {
                return getString(R.string.yandex_rate_limit);
            }
            if (httpError.getStatusCode() >= 500) {
                return getString(R.string.yandex_unavailable);
            }
            return getString(R.string.yandex_request_error, httpError.getErrorCode());
        }
        if (error instanceof IOException) {
            return getString(R.string.network_error);
        }
        return getString(R.string.unexpected_error);
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
        if (finishTabSwipeRunnable != null) {
            mainHandler.removeCallbacks(finishTabSwipeRunnable);
            finishTabSwipeRunnable = null;
        }
        cancelPendingSearch();
        cancelAuthorizationPolling();
        backgroundExecutor.shutdownNow();
        episodeStore.close();
        super.onDestroy();
    }
}
