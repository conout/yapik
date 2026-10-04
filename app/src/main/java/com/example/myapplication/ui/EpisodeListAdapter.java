package com.example.myapplication.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.model.Episode;
import com.example.myapplication.storage.EpisodeStore;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EpisodeListAdapter extends BaseAdapter {

    private static final int TYPE_EPISODE = 0;
    private static final int TYPE_SECTION = 1;

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expandedEpisodeIds = new HashSet<>();
    private final Map<String, ProgressState> progressByEpisode = new HashMap<>();
    private final EpisodeActionListener actionListener;
    private final boolean audiobook;
    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"));
    private String activeEpisodeId;
    private boolean activeEpisodePlaying;
    private boolean activeEpisodeLoading;

    public EpisodeListAdapter(
            Context context,
            List<Episode> episodes,
            Map<String, EpisodeStore.PlaybackProgress> storedProgress,
            boolean audiobook,
            EpisodeActionListener actionListener
    ) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        this.audiobook = audiobook;
        this.actionListener = actionListener;
        for (Map.Entry<String, EpisodeStore.PlaybackProgress> entry : storedProgress.entrySet()) {
            EpisodeStore.PlaybackProgress progress = entry.getValue();
            progressByEpisode.put(entry.getKey(), new ProgressState(
                    progress.getPositionMs(),
                    progress.getDurationMs(),
                    progress.isCompleted()
            ));
        }
        buildRows(episodes);
    }

    private void buildRows(List<Episode> episodes) {
        String previousSection = null;
        for (Episode episode : episodes) {
            String section = episode.getCategoryTitle();
            if (section != null && !section.equals(previousSection)) {
                rows.add(Row.section(section));
                previousSection = section;
            }
            rows.add(Row.episode(episode));
        }
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Episode getItem(int position) {
        return rows.get(position).episode;
    }

    @Override
    public long getItemId(int position) {
        Episode episode = getItem(position);
        if (episode == null) {
            return -position - 1L;
        }
        try {
            return Long.parseLong(episode.getId());
        } catch (NumberFormatException ignored) {
            return position;
        }
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).episode == null ? TYPE_SECTION : TYPE_EPISODE;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItemViewType(position) == TYPE_EPISODE;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        if (row.episode == null) {
            TextView sectionView;
            if (convertView == null) {
                sectionView = (TextView) inflater.inflate(
                        R.layout.item_episode_section,
                        parent,
                        false
                );
            } else {
                sectionView = (TextView) convertView;
            }
            sectionView.setText(row.sectionTitle);
            return sectionView;
        }

        EpisodeViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_episode, parent, false);
            holder = new EpisodeViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (EpisodeViewHolder) convertView.getTag();
        }

        Episode episode = row.episode;
        holder.title.setText(episode.getTitle());
        bindDescription(holder.description, episode);
        holder.date.setText(audiobook
                ? context.getString(R.string.chapter_label)
                : dateFormatter.format(episode.getPublicationDate()));
        ProgressState progress = progressByEpisode.get(episode.getId());
        holder.duration.setText(formatEpisodeStatus(episode));
        if (progress != null && progress.completed) {
            holder.duration.setTextColor(context.getColor(R.color.listened_status));
        } else if (progress != null && progress.positionMs > 0) {
            holder.duration.setTextColor(context.getColor(R.color.remaining_status));
        } else {
            holder.duration.setTextColor(context.getColor(R.color.text_tertiary));
        }
        holder.newBadge.setVisibility(
                audiobook || episode.isSeen() ? View.GONE : View.VISIBLE
        );
        boolean isActive = episode.getId().equals(activeEpisodeId);
        boolean isLoading = isActive && activeEpisodeLoading;
        holder.playbackButton.setActivated(isActive);
        if (isLoading) {
            holder.playbackButton.setImageDrawable(null);
        } else {
            holder.playbackButton.setImageResource(
                    isActive && activeEpisodePlaying
                            ? R.drawable.ic_pause
                            : R.drawable.ic_play
            );
        }
        holder.playbackProgress.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        holder.playbackButton.setContentDescription(context.getString(
                isLoading
                        ? R.string.preparing_playback
                        : isActive && activeEpisodePlaying ? R.string.pause : R.string.play
        ));
        holder.playbackButton.setOnClickListener(view -> actionListener.onEpisodeAction(episode));
        return convertView;
    }

    public void setPlaybackState(
            String episodeId,
            boolean playing,
            boolean loading,
            long positionMs,
            long durationMs
    ) {
        activeEpisodeId = episodeId;
        activeEpisodePlaying = playing;
        activeEpisodeLoading = loading;
        if (episodeId != null && (positionMs > 0 || durationMs > 0)) {
            ProgressState previous = progressByEpisode.get(episodeId);
            boolean completed = previous != null && previous.completed;
            progressByEpisode.put(episodeId, new ProgressState(
                    Math.max(0, positionMs),
                    Math.max(0, durationMs),
                    completed
            ));
        }
        notifyDataSetChanged();
    }

    public int findPositionByEpisodeId(String episodeId) {
        if (episodeId == null) {
            return -1;
        }
        for (int index = 0; index < rows.size(); index++) {
            Episode episode = rows.get(index).episode;
            if (episode != null && episodeId.equals(episode.getId())) {
                return index;
            }
        }
        return -1;
    }

    private void bindDescription(TextView descriptionView, Episode episode) {
        String description = episode.getDescription();
        boolean hasDescription = description != null && !description.trim().isEmpty();
        descriptionView.setText(description);
        descriptionView.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        descriptionView.setClickable(hasDescription);
        descriptionView.setFocusable(false);
        applyDescriptionState(descriptionView, expandedEpisodeIds.contains(episode.getId()));
        if (!hasDescription) {
            descriptionView.setOnClickListener(null);
            return;
        }

        descriptionView.setOnClickListener(view -> {
            boolean expanded;
            if (expandedEpisodeIds.remove(episode.getId())) {
                expanded = false;
            } else {
                expandedEpisodeIds.add(episode.getId());
                expanded = true;
            }
            applyDescriptionState(descriptionView, expanded);
            descriptionView.requestLayout();
        });
    }

    private void applyDescriptionState(TextView descriptionView, boolean expanded) {
        descriptionView.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
        descriptionView.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatEpisodeStatus(Episode episode) {
        ProgressState progress = progressByEpisode.get(episode.getId());
        if (progress == null) {
            return formatDuration(episode.getDurationMs());
        }
        if (progress.completed) {
            return context.getString(R.string.listened);
        }
        if (progress.positionMs <= 0) {
            return formatDuration(episode.getDurationMs());
        }
        long durationMs = progress.durationMs > 0
                ? progress.durationMs
                : episode.getDurationMs();
        long remainingMs = Math.max(0, durationMs - progress.positionMs);
        int remainingMinutes = (int) Math.max(1, (remainingMs + 59_999L) / 60_000L);
        return context.getResources().getQuantityString(
                R.plurals.minutes_remaining,
                remainingMinutes,
                remainingMinutes
        );
    }

    public interface EpisodeActionListener {
        void onEpisodeAction(Episode episode);
    }

    private static final class ProgressState {

        final long positionMs;
        final long durationMs;
        final boolean completed;

        ProgressState(long positionMs, long durationMs, boolean completed) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.completed = completed;
        }
    }

    private static final class Row {

        final Episode episode;
        final String sectionTitle;

        private Row(Episode episode, String sectionTitle) {
            this.episode = episode;
            this.sectionTitle = sectionTitle;
        }

        static Row episode(Episode episode) {
            return new Row(episode, null);
        }

        static Row section(String title) {
            return new Row(null, title);
        }
    }

    private static final class EpisodeViewHolder {

        final TextView title;
        final TextView description;
        final TextView date;
        final TextView duration;
        final TextView newBadge;
        final ImageButton playbackButton;
        final ProgressBar playbackProgress;

        EpisodeViewHolder(View view) {
            title = view.findViewById(R.id.episodeTitle);
            description = view.findViewById(R.id.episodeDescription);
            date = view.findViewById(R.id.episodeDate);
            duration = view.findViewById(R.id.episodeDuration);
            newBadge = view.findViewById(R.id.episodeNewBadge);
            playbackButton = view.findViewById(R.id.episodePlaybackButton);
            playbackProgress = view.findViewById(R.id.episodePlaybackProgress);
        }
    }
}
