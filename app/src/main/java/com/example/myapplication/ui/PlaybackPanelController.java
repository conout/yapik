package com.example.myapplication.ui;

import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.playback.PlaybackService;

import java.util.Locale;

public final class PlaybackPanelController {

    private final View root;
    private final TextView episodeTitle;
    private final TextView status;
    private final TextView elapsed;
    private final TextView duration;
    private final SeekBar seekBar;
    private final ImageButton playPause;
    private final ProgressBar playPauseProgress;
    private final ImageButton previous;
    private final ImageButton next;
    private final Button speed;
    private final Listener listener;
    private boolean seekInProgress;
    private long durationMs;
    private float playbackSpeed = 1f;

    public PlaybackPanelController(View root, Listener listener) {
        this.root = root;
        this.listener = listener;
        episodeTitle = root.findViewById(R.id.playbackEpisodeTitle);
        status = root.findViewById(R.id.playbackStatus);
        elapsed = root.findViewById(R.id.playbackElapsed);
        duration = root.findViewById(R.id.playbackDuration);
        seekBar = root.findViewById(R.id.playbackSeekBar);
        playPause = root.findViewById(R.id.playbackPlayPause);
        playPauseProgress = root.findViewById(R.id.playbackPlayPauseProgress);
        previous = root.findViewById(R.id.playbackPrevious);
        next = root.findViewById(R.id.playbackNext);
        speed = root.findViewById(R.id.playbackSpeed);
        bindActions();
    }

    private void bindActions() {
        playPause.setOnClickListener(view -> listener.onToggle());
        previous.setOnClickListener(view -> listener.onPrevious());
        next.setOnClickListener(view -> listener.onNext());
        root.findViewById(R.id.playbackRewind).setOnClickListener(
                view -> listener.onSeekBy(-15_000)
        );
        root.findViewById(R.id.playbackForward).setOnClickListener(
                view -> listener.onSeekBy(15_000)
        );
        speed.setOnClickListener(view -> listener.onSetSpeed(nextSpeed(playbackSpeed)));
        episodeTitle.setContentDescription(root.getContext().getString(
                R.string.open_current_episode
        ));
        status.setContentDescription(root.getContext().getString(
                R.string.open_current_episode
        ));
        episodeTitle.setOnClickListener(view -> listener.onOpenCurrentEpisode());
        status.setOnClickListener(view -> listener.onOpenCurrentEpisode());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    elapsed.setText(formatTime(durationMs * progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seekInProgress = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seekInProgress = false;
                listener.onSeekTo(durationMs * bar.getProgress() / 1000);
            }
        });
    }

    public void render(PlaybackService.Snapshot snapshot) {
        boolean hasEpisode = snapshot.getEpisodeId() != null;
        root.setVisibility(hasEpisode ? View.VISIBLE : View.GONE);
        if (!hasEpisode) {
            return;
        }
        durationMs = snapshot.getDurationMs();
        playbackSpeed = snapshot.getSpeed();
        episodeTitle.setText(snapshot.getEpisodeTitle());
        if (snapshot.getErrorMessage() != null) {
            status.setText(snapshot.getErrorMessage());
        } else if (snapshot.isLoading()) {
            status.setText(R.string.preparing_playback);
        } else {
            status.setText(snapshot.getPodcastTitle());
        }
        if (snapshot.isLoading()) {
            playPause.setImageDrawable(null);
        } else {
            playPause.setImageResource(
                    snapshot.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play
            );
        }
        playPauseProgress.setVisibility(snapshot.isLoading() ? View.VISIBLE : View.GONE);
        playPause.setContentDescription(root.getContext().getString(
                snapshot.isLoading()
                        ? R.string.preparing_playback
                        : snapshot.isPlaying() ? R.string.pause : R.string.play
        ));
        previous.setEnabled(snapshot.hasPrevious() || snapshot.getPositionMs() > 10_000);
        next.setEnabled(snapshot.hasNext());
        speed.setText(formatSpeed(snapshot.getSpeed()));
        elapsed.setText(formatTime(snapshot.getPositionMs()));
        duration.setText(formatTime(snapshot.getDurationMs()));
        if (!seekInProgress) {
            int progress = snapshot.getDurationMs() <= 0
                    ? 0
                    : (int) Math.min(
                            1000,
                            snapshot.getPositionMs() * 1000 / snapshot.getDurationMs()
                    );
            seekBar.setProgress(progress);
        }
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0, milliseconds) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatSpeed(float value) {
        if (value == (int) value) {
            return String.format(Locale.getDefault(), "%d×", (int) value);
        }
        if (value * 10 == (int) (value * 10)) {
            return String.format(Locale.getDefault(), "%.1f×", value);
        }
        return String.format(Locale.getDefault(), "%.2f×", value);
    }

    private float nextSpeed(float current) {
        if (current < 1f) return 1f;
        if (current < 1.25f) return 1.25f;
        if (current < 1.5f) return 1.5f;
        if (current < 1.75f) return 1.75f;
        if (current < 2f) return 2f;
        return 0.75f;
    }

    public interface Listener {
        void onToggle();
        void onPrevious();
        void onNext();
        void onSeekBy(long differenceMs);
        void onSeekTo(long positionMs);
        void onSetSpeed(float speed);
        void onOpenCurrentEpisode();
    }
}
