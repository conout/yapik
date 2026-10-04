package com.example.myapplication.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.model.DiscoveryItem;
import com.example.myapplication.model.Podcast;

import java.util.List;
import java.util.Locale;

public final class DiscoveryListAdapter extends BaseAdapter {

    private static final int[] COVER_COLORS = {
            Color.rgb(255, 204, 0),
            Color.rgb(176, 224, 230),
            Color.rgb(209, 196, 233),
            Color.rgb(255, 183, 178)
    };

    private final LayoutInflater inflater;
    private final List<DiscoveryItem> items;

    public DiscoveryListAdapter(Context context, List<DiscoveryItem> items) {
        inflater = LayoutInflater.from(context);
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public DiscoveryItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        Podcast podcast = getItem(position).getPodcast();
        return podcast == null ? position : podcast.getId();
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @Override
    public boolean isEnabled(int position) {
        return getItem(position).getType() != DiscoveryItem.TYPE_SECTION;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        DiscoveryItem item = getItem(position);
        if (item.getType() == DiscoveryItem.TYPE_SECTION) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_discovery_section, parent, false);
            }
            ((TextView) convertView).setText(item.getTitle());
            return convertView;
        }
        if (item.getType() == DiscoveryItem.TYPE_CATEGORY) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_discovery_category, parent, false);
            }
            ((TextView) convertView.findViewById(R.id.categoryTitle)).setText(item.getTitle());
            return convertView;
        }

        PodcastViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_podcast, parent, false);
            holder = new PodcastViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (PodcastViewHolder) convertView.getTag();
        }

        Podcast podcast = item.getPodcast();
        String coverLetter = podcast.getTitle().isEmpty()
                ? "?"
                : podcast.getTitle().substring(0, 1).toUpperCase(Locale.forLanguageTag("ru"));
        holder.coverLetter.setText(coverLetter);
        holder.coverLetter.setBackgroundTintList(
                ColorStateList.valueOf(COVER_COLORS[position % COVER_COLORS.length])
        );
        RemoteImageLoader.load(holder.coverImage, holder.coverLetter, podcast.getCoverUri());
        holder.coverPlaybackControl.setClickable(false);
        holder.coverPlaybackScrim.setVisibility(View.GONE);
        holder.coverPlaybackIcon.setVisibility(View.GONE);
        holder.title.setText(podcast.getTitle());
        holder.description.setText(podcast.getDescription());
        if (item.getSubtitle() != null && !item.getSubtitle().isEmpty()) {
            holder.subtitle.setText(item.getSubtitle());
        } else if (podcast.isAudiobook() && podcast.getEpisodeCount() == 0) {
            holder.subtitle.setText(R.string.audiobook_label);
        } else {
            holder.subtitle.setText(parent.getResources().getQuantityString(
                    podcast.isAudiobook()
                            ? R.plurals.chapters_count
                            : R.plurals.episodes_count,
                    podcast.getEpisodeCount(),
                    podcast.getEpisodeCount()
            ));
        }
        holder.annotation.setText(item.getAnnotation() == null ? "" : item.getAnnotation());
        holder.badge.setVisibility(View.GONE);
        return convertView;
    }

    private static final class PodcastViewHolder {
        final TextView coverLetter;
        final ImageView coverImage;
        final View coverPlaybackControl;
        final View coverPlaybackScrim;
        final ImageView coverPlaybackIcon;
        final TextView title;
        final TextView description;
        final TextView subtitle;
        final TextView annotation;
        final TextView badge;

        PodcastViewHolder(View view) {
            coverLetter = view.findViewById(R.id.coverLetter);
            coverImage = view.findViewById(R.id.podcastCover);
            coverPlaybackControl = view.findViewById(R.id.coverPlaybackControl);
            coverPlaybackScrim = view.findViewById(R.id.coverPlaybackScrim);
            coverPlaybackIcon = view.findViewById(R.id.coverPlaybackIcon);
            title = view.findViewById(R.id.podcastTitle);
            description = view.findViewById(R.id.podcastDescription);
            subtitle = view.findViewById(R.id.latestEpisode);
            annotation = view.findViewById(R.id.publicationDate);
            badge = view.findViewById(R.id.newEpisodeBadge);
        }
    }
}
