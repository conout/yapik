package com.example.myapplication.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.model.Episode;
import com.example.myapplication.model.Podcast;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class PodcastListAdapter extends BaseAdapter {

    private static final int[] COVER_COLORS = {
            Color.rgb(255, 204, 0),
            Color.rgb(176, 224, 230),
            Color.rgb(209, 196, 233),
            Color.rgb(255, 183, 178)
    };

    private final LayoutInflater inflater;
    private final List<Podcast> podcasts;
    private final CoverActionListener coverActionListener;
    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"));
    private long playingPodcastId;
    private boolean playing;
    private boolean loading;

    public PodcastListAdapter(
            Context context,
            List<Podcast> podcasts,
            CoverActionListener coverActionListener
    ) {
        this.inflater = LayoutInflater.from(context);
        this.podcasts = podcasts;
        this.coverActionListener = coverActionListener;
    }

    public void setPlaybackState(long podcastId, boolean playing, boolean loading) {
        if (playingPodcastId == podcastId
                && this.playing == playing
                && this.loading == loading) {
            return;
        }
        playingPodcastId = podcastId;
        this.playing = playing;
        this.loading = loading;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return podcasts.size();
    }

    @Override
    public Podcast getItem(int position) {
        return podcasts.get(position);
    }

    @Override
    public long getItemId(int position) {
        return podcasts.get(position).getId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_podcast, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Podcast podcast = getItem(position);
        Episode latestEpisode = podcast.getLatestEpisode();
        int newEpisodeCount = podcast.isAudiobook() ? 0 : podcast.getNewEpisodeCount();

        String coverLetter = podcast.getTitle().isEmpty()
                ? "?"
                : podcast.getTitle().substring(0, 1).toUpperCase(Locale.forLanguageTag("ru"));
        holder.coverLetter.setText(coverLetter);
        holder.coverLetter.setBackgroundTintList(
                ColorStateList.valueOf(COVER_COLORS[position % COVER_COLORS.length])
        );
        RemoteImageLoader.load(holder.coverImage, holder.coverLetter, podcast.getCoverUri());
        boolean podcastIsPlaying = playing && playingPodcastId == podcast.getId();
        boolean podcastIsLoading = loading && playingPodcastId == podcast.getId();
        holder.coverPlaybackControl.setClickable(true);
        holder.coverPlaybackScrim.setVisibility(View.VISIBLE);
        holder.coverPlaybackIcon.setVisibility(podcastIsLoading ? View.GONE : View.VISIBLE);
        holder.coverPlaybackProgress.setVisibility(
                podcastIsLoading ? View.VISIBLE : View.GONE
        );
        holder.coverPlaybackIcon.setImageResource(
                podcastIsPlaying ? R.drawable.ic_pause : R.drawable.ic_play
        );
        holder.coverPlaybackControl.setContentDescription(parent.getResources().getString(
                podcastIsLoading
                        ? R.string.preparing_playback
                        : podcastIsPlaying ? R.string.pause : R.string.play
        ));
        holder.coverPlaybackControl.setOnClickListener(
                view -> coverActionListener.onCoverAction(podcast)
        );
        holder.title.setText(podcast.getTitle());
        holder.description.setText(podcast.getDescription());

        if (latestEpisode == null) {
            if (podcast.getEpisodeCount() == 0) {
                holder.latestEpisode.setText(
                        podcast.isAudiobook() ? R.string.audiobook_label : R.string.no_episodes
                );
            } else {
                int countResource = podcast.isAudiobook()
                        ? R.plurals.chapters_count
                        : R.plurals.episodes_count;
                holder.latestEpisode.setText(parent.getResources().getQuantityString(
                        countResource,
                        podcast.getEpisodeCount(),
                        podcast.getEpisodeCount()
                ));
            }
            holder.publicationDate.setText(
                    podcast.isAudiobook() ? R.string.audiobook_label : R.string.podcast_label
            );
        } else {
            holder.latestEpisode.setText(latestEpisode.getTitle());
            holder.publicationDate.setText(dateFormatter.format(latestEpisode.getPublicationDate()));
        }

        if (newEpisodeCount == 0) {
            holder.newEpisodeBadge.setVisibility(View.GONE);
        } else {
            holder.newEpisodeBadge.setVisibility(View.VISIBLE);
            holder.newEpisodeBadge.setText(String.valueOf(newEpisodeCount));
        }

        return convertView;
    }

    private static final class ViewHolder {

        final View coverPlaybackControl;
        final View coverPlaybackScrim;
        final TextView coverLetter;
        final ImageView coverImage;
        final ImageView coverPlaybackIcon;
        final ProgressBar coverPlaybackProgress;
        final TextView title;
        final TextView description;
        final TextView latestEpisode;
        final TextView publicationDate;
        final TextView newEpisodeBadge;

        ViewHolder(View view) {
            coverPlaybackControl = view.findViewById(R.id.coverPlaybackControl);
            coverPlaybackScrim = view.findViewById(R.id.coverPlaybackScrim);
            coverLetter = view.findViewById(R.id.coverLetter);
            coverImage = view.findViewById(R.id.podcastCover);
            coverPlaybackIcon = view.findViewById(R.id.coverPlaybackIcon);
            coverPlaybackProgress = view.findViewById(R.id.coverPlaybackProgress);
            title = view.findViewById(R.id.podcastTitle);
            description = view.findViewById(R.id.podcastDescription);
            latestEpisode = view.findViewById(R.id.latestEpisode);
            publicationDate = view.findViewById(R.id.publicationDate);
            newEpisodeBadge = view.findViewById(R.id.newEpisodeBadge);
        }
    }

    public interface CoverActionListener {
        void onCoverAction(Podcast podcast);
    }
}
