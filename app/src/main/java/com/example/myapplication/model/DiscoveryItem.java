package com.example.myapplication.model;

public final class DiscoveryItem {

    public static final int TYPE_SECTION = 0;
    public static final int TYPE_PODCAST = 1;
    public static final int TYPE_CATEGORY = 2;

    private final int type;
    private final String title;
    private final String subtitle;
    private final String annotation;
    private final Podcast podcast;
    private final String episodeId;
    private final String categoryPath;

    private DiscoveryItem(
            int type,
            String title,
            String subtitle,
            String annotation,
            Podcast podcast,
            String episodeId,
            String categoryPath
    ) {
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.annotation = annotation;
        this.podcast = podcast;
        this.episodeId = episodeId;
        this.categoryPath = categoryPath;
    }

    public static DiscoveryItem section(String title) {
        return new DiscoveryItem(TYPE_SECTION, title, null, null, null, null, null);
    }

    public static DiscoveryItem podcast(
            Podcast podcast,
            String subtitle,
            String annotation,
            String episodeId
    ) {
        return new DiscoveryItem(
                TYPE_PODCAST,
                podcast.getTitle(),
                subtitle,
                annotation,
                podcast,
                episodeId,
                null
        );
    }

    public static DiscoveryItem category(String title, String categoryPath) {
        return new DiscoveryItem(
                TYPE_CATEGORY,
                title,
                null,
                null,
                null,
                null,
                categoryPath
        );
    }

    public int getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getAnnotation() {
        return annotation;
    }

    public Podcast getPodcast() {
        return podcast;
    }

    public String getEpisodeId() {
        return episodeId;
    }

    public String getCategoryPath() {
        return categoryPath;
    }
}
