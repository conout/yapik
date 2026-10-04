package com.example.myapplication.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Podcast {

    public enum ContentType {
        PODCAST,
        AUDIOBOOK
    }

    private final long id;
    private final String title;
    private final String description;
    private final String coverUri;
    private final Instant likedAt;
    private final int episodeCount;
    private final List<Episode> episodes;
    private final ContentType contentType;
    private int newEpisodeCount;

    public Podcast(
            long id,
            String title,
            String description,
            String coverUri,
            Instant likedAt,
            int episodeCount,
            int newEpisodeCount,
            List<Episode> episodes,
            ContentType contentType
    ) {
        this.id = id;
        this.title = Objects.requireNonNull(title);
        this.description = description;
        this.coverUri = coverUri;
        this.likedAt = likedAt;
        this.episodeCount = Math.max(episodeCount, episodes.size());
        this.episodes = new ArrayList<>(episodes);
        this.contentType = contentType == null ? ContentType.PODCAST : contentType;
        this.newEpisodeCount = Math.max(0, newEpisodeCount);
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCoverUri() {
        return coverUri;
    }

    public Instant getLikedAt() {
        return likedAt;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }

    public Podcast withNewEpisodeCount(int count) {
        return new Podcast(
                id,
                title,
                description,
                coverUri,
                likedAt,
                episodeCount,
                count,
                episodes,
                contentType
        );
    }

    public ContentType getContentType() {
        return contentType;
    }

    public boolean isAudiobook() {
        return contentType == ContentType.AUDIOBOOK;
    }

    public int getNewEpisodeCount() {
        return newEpisodeCount;
    }

    public Episode getLatestEpisode() {
        return episodes.stream()
                .max(Comparator.comparing(Episode::getPublicationDate))
                .orElse(null);
    }

    public void markAllEpisodesSeen() {
        for (Episode episode : episodes) {
            episode.markSeen();
        }
        newEpisodeCount = 0;
    }
}
