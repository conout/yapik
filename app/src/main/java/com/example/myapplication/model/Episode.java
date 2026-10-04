package com.example.myapplication.model;

import java.time.LocalDate;
import java.util.Objects;

public final class Episode {

    private final String id;
    private final long podcastId;
    private final String title;
    private final String description;
    private final LocalDate publicationDate;
    private final long durationMs;
    private final int categoryIndex;
    private final String categoryTitle;
    private boolean seen;
    private boolean listened;
    private long playbackPositionMs;

    public Episode(
            String id,
            long podcastId,
            String title,
            String description,
            LocalDate publicationDate,
            long durationMs,
            boolean seen,
            int categoryIndex,
            String categoryTitle
    ) {
        this.id = Objects.requireNonNull(id);
        this.podcastId = podcastId;
        this.title = Objects.requireNonNull(title);
        this.description = description;
        this.publicationDate = Objects.requireNonNull(publicationDate);
        this.durationMs = durationMs;
        this.seen = seen;
        this.categoryIndex = Math.max(0, categoryIndex);
        this.categoryTitle = categoryTitle;
    }

    public String getId() {
        return id;
    }

    public long getPodcastId() {
        return podcastId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getPublicationDate() {
        return publicationDate;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getCategoryIndex() {
        return categoryIndex;
    }

    public String getCategoryTitle() {
        return categoryTitle;
    }

    public boolean isSeen() {
        return seen;
    }

    public void markSeen() {
        seen = true;
    }

    public boolean isListened() {
        return listened;
    }

    public void setListened(boolean listened) {
        this.listened = listened;
    }

    public long getPlaybackPositionMs() {
        return playbackPositionMs;
    }

    public void setPlaybackPositionMs(long playbackPositionMs) {
        this.playbackPositionMs = Math.max(0, playbackPositionMs);
    }
}
