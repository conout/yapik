package com.example.myapplication.data;

import com.example.myapplication.model.Episode;
import com.example.myapplication.model.DiscoveryItem;
import com.example.myapplication.model.Podcast;
import com.example.myapplication.model.UserProfile;
import com.example.myapplication.network.JsonHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YandexMusicRepository {

    private static final String API_BASE_URL = "https://api.music.yandex.net";
    private static final String PROGRESS_API_BASE_URL = "https://api.music.yandex.ru";

    private final JsonHttpClient httpClient;

    public YandexMusicRepository(JsonHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<DiscoveryItem> loadPodcastDiscovery(String accessToken)
            throws IOException, JSONException {
        Map<String, String> headers = authorizationHeaders(accessToken);
        List<DiscoveryItem> result = new ArrayList<>();
        Exception lastError = null;

        try {
            JSONObject response = httpClient.get(API_BASE_URL + "/chart/podcasts", headers);
            List<DiscoveryItem> chart = parseChart(response);
            appendSection(result, "Чарт подкастов", limit(chart, 10));
        } catch (IOException | JSONException error) {
            lastError = error;
        }

        try {
            JSONObject response = httpClient.get(
                    API_BASE_URL
                            + "/landing/block/non-music/open-playlist/new_episodes_web?uid="
                            + loadCurrentUserId(headers),
                    headers
            );
            List<DiscoveryItem> episodes = new ArrayList<>();
            collectPodcastEpisodes(response, episodes, new HashSet<>());
            if (episodes.isEmpty()) {
                episodes = podcastItems(response, "Новый выпуск", 12);
            }
            appendSection(result, "Громкие новинки", limit(episodes, 12));
        } catch (IOException | JSONException error) {
            lastError = error;
        }

        try {
            JSONObject response = httpClient.get(
                    API_BASE_URL
                            + "/landing/block/non-music/editorial/compilation/"
                            + "podcast_editorial_web",
                    headers
            );
            appendSection(
                    result,
                    "Выбор редакции",
                    limit(podcastItems(response, null, 12), 12)
            );
        } catch (IOException | JSONException error) {
            lastError = error;
        }

        try {
            JSONObject response = httpClient.get(
                    API_BASE_URL + "/landing/block/mixes/grid/podcasts_mixes_grid",
                    headers
            );
            List<DiscoveryItem> categories = new ArrayList<>();
            collectCategories(response, categories, new HashSet<>());
            appendSection(result, "Подкасты по категориям", categories);
        } catch (IOException | JSONException error) {
            lastError = error;
        }

        if (result.isEmpty()) {
            if (lastError instanceof IOException) {
                throw (IOException) lastError;
            }
            if (lastError instanceof JSONException) {
                throw (JSONException) lastError;
            }
            throw new IOException("Yandex returned no podcast discovery sections");
        }
        return result;
    }

    public List<DiscoveryItem> loadBookDiscovery(String accessToken)
            throws IOException, JSONException {
        Map<String, String> headers = authorizationHeaders(accessToken);
        List<DiscoveryItem> result = new ArrayList<>();
        Exception lastError = null;

        String[][] blocks = {
                {
                        "/landing/block/chart/album/editorial_audiobooks_CHART",
                        "Чарт аудиокниг"
                },
                {
                        "/landing/block/non-music/editorial/compilation/audiobooks_editorial",
                        "Выбор редакции"
                },
                {
                        "/landing/block/non-music/editorial/compilation/new_arrivals",
                        "Новые поступления"
                }
        };
        for (String[] block : blocks) {
            try {
                JSONObject response = httpClient.get(API_BASE_URL + block[0], headers);
                List<DiscoveryItem> items = limit(
                        podcastItems(
                                response,
                                null,
                                12,
                                Podcast.ContentType.AUDIOBOOK
                        ),
                        12
                );
                appendSection(result, block[1], items);
            } catch (IOException | JSONException error) {
                lastError = error;
            }
        }

        if (result.isEmpty()) {
            try {
                JSONObject response = httpClient.get(
                        API_BASE_URL + "/non-music/calague",
                        headers
                );
                JSONObject catalogue = response.optJSONObject("result");
                JSONArray catalogueBlocks = catalogue == null
                        ? null
                        : catalogue.optJSONArray("blocks");
                if (catalogueBlocks != null) {
                    for (int index = 0; index < catalogueBlocks.length(); index++) {
                        JSONObject block = catalogueBlocks.optJSONObject(index);
                        if (block == null) {
                            continue;
                        }
                        List<DiscoveryItem> books = podcastItems(
                                block,
                                null,
                                12,
                                Podcast.ContentType.AUDIOBOOK
                        );
                        appendSection(
                                result,
                                firstNotEmpty(
                                        block.optString("title", ""),
                                        "Подборка книг"
                                ),
                                books
                        );
                    }
                }
            } catch (IOException | JSONException error) {
                lastError = error;
            }
        }

        try {
            JSONObject response = httpClient.get(
                    API_BASE_URL + "/landing/block/mixes/grid/audiobooks_mixes_grid",
                    headers
            );
            List<DiscoveryItem> categories = new ArrayList<>();
            collectCategories(response, categories, new HashSet<>());
            appendSection(result, "Книги по жанрам", categories);
        } catch (IOException | JSONException error) {
            lastError = error;
        }

        if (result.isEmpty()) {
            if (lastError instanceof IOException) {
                throw (IOException) lastError;
            }
            if (lastError instanceof JSONException) {
                throw (JSONException) lastError;
            }
            throw new IOException("Yandex returned no audiobook discovery sections");
        }
        return result;
    }

    public List<DiscoveryItem> searchPodcasts(String accessToken, String query, int page)
            throws IOException, JSONException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject response = httpClient.get(
                API_BASE_URL + "/search?text=" + encodedQuery
                        + "&type=podcast&page=" + Math.max(0, page)
                        + "&nocorrect=false",
                authorizationHeaders(accessToken)
        );
        JSONObject result = response.optJSONObject("result");
        JSONObject podcasts = result == null ? null : result.optJSONObject("podcasts");
        JSONArray items = podcasts == null ? null : podcasts.optJSONArray("results");
        return podcastItems(
                items,
                null,
                Integer.MAX_VALUE,
                Podcast.ContentType.PODCAST
        );
    }

    public List<DiscoveryItem> searchBooks(String accessToken, String query, int page)
            throws IOException, JSONException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject response = httpClient.get(
                API_BASE_URL + "/search?text=" + encodedQuery
                        + "&type=all&page=" + Math.max(0, page)
                        + "&nocorrect=false",
                authorizationHeaders(accessToken)
        );
        return podcastItems(
                response,
                null,
                Integer.MAX_VALUE,
                Podcast.ContentType.AUDIOBOOK
        );
    }

    public List<DiscoveryItem> loadPodcastCategory(
            String accessToken,
            String categoryPath,
            String categoryTitle
    )
            throws IOException, JSONException {
        String path = normalizeCategoryPath(categoryPath);
        try {
            JSONObject response = httpClient.get(
                    API_BASE_URL + path,
                    authorizationHeaders(accessToken)
            );
            List<DiscoveryItem> items = podcastItems(response, null, Integer.MAX_VALUE);
            if (!items.isEmpty()) {
                return items;
            }
        } catch (IOException | JSONException ignored) {
            // Category landing pages may require a browser cookie instead of OAuth.
        }
        return searchPodcasts(accessToken, categoryTitle, 0);
    }

    public List<DiscoveryItem> loadBookCategory(
            String accessToken,
            String categoryPath,
            String categoryTitle
    ) throws IOException, JSONException {
        String path = normalizeCategoryPath(categoryPath);
        if (path.startsWith("/landing/category_")) {
            try {
                Map<String, String> headers = authorizationHeaders(accessToken);
                String categoryId = path.substring("/landing/".length());
                JSONObject skeleton = httpClient.get(
                        API_BASE_URL + "/landing/skeleton/" + categoryId,
                        headers
                );
                List<String> blockPaths = new ArrayList<>();
                collectLandingBlockPaths(skeleton, blockPaths, new HashSet<>());
                List<DiscoveryItem> books = new ArrayList<>();
                Set<Long> seenBookIds = new HashSet<>();
                for (String blockPath : blockPaths) {
                    try {
                        JSONObject block = httpClient.get(
                                API_BASE_URL + blockPath
                                        + (blockPath.contains("?") ? "&" : "?")
                                        + "count=50&countWeb=50",
                                headers
                        );
                        for (DiscoveryItem item : podcastItems(
                                block,
                                null,
                                Integer.MAX_VALUE,
                                Podcast.ContentType.AUDIOBOOK
                        )) {
                            Podcast book = item.getPodcast();
                            if (book != null && seenBookIds.add(book.getId())) {
                                books.add(item);
                            }
                        }
                    } catch (IOException | JSONException ignored) {
                        // One unavailable shelf should not hide the rest of the genre.
                    }
                }
                if (!books.isEmpty()) {
                    return books;
                }
            } catch (IOException | JSONException ignored) {
                // The public catalogue page can occasionally be unavailable.
            }
        } else {
            if (path.startsWith("/non-music/editorial/album/")) {
                path = "/landing/block" + path;
            }
            try {
                JSONObject response = httpClient.get(
                        API_BASE_URL + path,
                        authorizationHeaders(accessToken)
                );
                List<DiscoveryItem> items = podcastItems(
                        response,
                        null,
                        Integer.MAX_VALUE,
                        Podcast.ContentType.AUDIOBOOK
                );
                if (!items.isEmpty()) {
                    return items;
                }
            } catch (IOException | JSONException ignored) {
                // Some landing pages are not exposed to every account or region.
            }
        }

        String encodedQuery = URLEncoder.encode(
                categoryTitle,
                StandardCharsets.UTF_8.name()
        );
        JSONObject response = httpClient.get(
                API_BASE_URL + "/search?text=" + encodedQuery
                        + "&type=all&page=0&nocorrect=false",
                authorizationHeaders(accessToken)
        );
        return podcastItems(
                response,
                null,
                Integer.MAX_VALUE,
                Podcast.ContentType.AUDIOBOOK
        );
    }

    private void collectLandingBlockPaths(
            Object node,
            List<String> result,
            Set<String> seenPaths
    ) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String uri = object.optString("uri", "");
            if (uri.startsWith("/landing/block/") && seenPaths.add(uri)) {
                result.add(uri);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectLandingBlockPaths(object.opt(keys.next()), result, seenPaths);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectLandingBlockPaths(array.opt(index), result, seenPaths);
            }
        }
    }

    private List<DiscoveryItem> parseChart(JSONObject response) {
        List<DiscoveryItem> items = new ArrayList<>();
        JSONObject result = response.optJSONObject("result");
        JSONArray positions = result == null ? null : result.optJSONArray("chartPositions");
        if (positions == null) {
            return items;
        }
        for (int index = 0; index < positions.length(); index++) {
            JSONObject position = positions.optJSONObject(index);
            Podcast podcast = position == null ? null : parsePodcast(position.optJSONObject("album"));
            if (podcast == null) {
                continue;
            }
            JSONObject chartPosition = position.optJSONObject("chartPosition");
            int rank = chartPosition == null
                    ? index + 1
                    : chartPosition.optInt("position", index + 1);
            String progress = chartPosition == null
                    ? ""
                    : chartPosition.optString("progress", "");
            String marker = "№ " + rank;
            if ("new".equals(progress)) {
                marker += " · новый";
            } else if ("up".equals(progress)) {
                marker += " · ↑";
            } else if ("down".equals(progress)) {
                marker += " · ↓";
            }
            items.add(DiscoveryItem.podcast(podcast, null, marker, null));
        }
        return items;
    }

    private List<DiscoveryItem> podcastItems(Object node, String annotation, int limit) {
        return podcastItems(node, annotation, limit, null);
    }

    private List<DiscoveryItem> podcastItems(
            Object node,
            String annotation,
            int limit,
            Podcast.ContentType requiredType
    ) {
        LinkedHashMap<Long, Podcast> podcasts = new LinkedHashMap<>();
        collectPodcasts(node, podcasts, requiredType);
        List<DiscoveryItem> result = new ArrayList<>();
        for (Podcast podcast : podcasts.values()) {
            result.add(DiscoveryItem.podcast(podcast, null, annotation, null));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private void collectPodcasts(
            Object node,
            Map<Long, Podcast> result,
            Podcast.ContentType requiredType
    ) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Podcast podcast = parsePodcast(object);
            if (podcast != null
                    && (requiredType == null || podcast.getContentType() == requiredType)) {
                result.putIfAbsent(podcast.getId(), podcast);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectPodcasts(object.opt(keys.next()), result, requiredType);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectPodcasts(array.opt(index), result, requiredType);
            }
        }
    }

    private void collectPodcastEpisodes(
            Object node,
            List<DiscoveryItem> result,
            Set<String> seenEpisodeIds
    ) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray albums = object.optJSONArray("albums");
            String episodeId = object.optString("id", "");
            String type = object.optString("type", "");
            if (!episodeId.isEmpty()
                    && albums != null
                    && albums.length() > 0
                    && ("podcast-episode".equals(type) || object.has("durationMs"))) {
                Podcast podcast = parsePodcast(albums.optJSONObject(0));
                if (podcast != null && seenEpisodeIds.add(episodeId)) {
                    result.add(DiscoveryItem.podcast(
                            podcast,
                            object.optString("title", "Новый выпуск"),
                            "Новый выпуск",
                            episodeId
                    ));
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectPodcastEpisodes(object.opt(keys.next()), result, seenEpisodeIds);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectPodcastEpisodes(array.opt(index), result, seenEpisodeIds);
            }
        }
    }

    private void collectCategories(
            Object node,
            List<DiscoveryItem> result,
            Set<String> seenPaths
    ) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String title = object.optString("title", "");
            String path = findCategoryPath(object);
            if (!title.isEmpty() && path != null && seenPaths.add(path)) {
                result.add(DiscoveryItem.category(title, path));
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectCategories(object.opt(keys.next()), result, seenPaths);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectCategories(array.opt(index), result, seenPaths);
            }
        }
    }

    private String findCategoryPath(JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object value = object.opt(keys.next());
            if (value instanceof String) {
                String string = (String) value;
                if (string.contains("category_non-music_")) {
                    return normalizeCategoryPath(string);
                }
                int editorialIndex = string.indexOf("/non-music/editorial/album/");
                if (editorialIndex >= 0) {
                    return string.substring(editorialIndex);
                }
            }
        }
        for (String key : new String[]{"action", "url", "link", "data"}) {
            JSONObject nested = object.optJSONObject(key);
            if (nested != null) {
                String path = findCategoryPath(nested);
                if (path != null) {
                    return path;
                }
            }
        }
        return null;
    }

    private String normalizeCategoryPath(String value) {
        int landingIndex = value.indexOf("/landing/");
        if (landingIndex >= 0) {
            return value.substring(landingIndex);
        }
        int categoryIndex = value.indexOf("category_non-music_");
        if (categoryIndex >= 0) {
            return "/landing/" + value.substring(categoryIndex);
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private Podcast parsePodcast(JSONObject source) {
        if (source == null) {
            return null;
        }
        JSONObject album = source.optJSONObject("album");
        if (album == null) {
            album = source;
        }
        String type = album.optString("type", "");
        String metaType = album.optString("metaType", "");
        String albumType = album.optString("albumType", "");
        Podcast.ContentType contentType;
        if ("audiobook".equals(type)
                || "audiobook".equals(metaType)
                || "audiobook".equals(albumType)) {
            contentType = Podcast.ContentType.AUDIOBOOK;
        } else if ("podcast".equals(type) || "podcast".equals(metaType)) {
            contentType = Podcast.ContentType.PODCAST;
        } else {
            return null;
        }
        long id = album.optLong("id", 0);
        String title = album.optString("title", "");
        if (id == 0 || title.isEmpty()) {
            return null;
        }
        JSONObject cover = album.optJSONObject("cover");
        return new Podcast(
                id,
                title,
                firstNotEmpty(
                        album.optString("shortDescription", ""),
                        album.optString("description", ""),
                        contentType == Podcast.ContentType.AUDIOBOOK
                                ? "Аудиокнига Яндекс Музыки"
                                : "Подкаст Яндекс Музыки"
                ),
                firstNotEmpty(
                        album.optString("coverUri", ""),
                        cover == null ? "" : cover.optString("uri", "")
                ),
                null,
                album.optInt("trackCount", 0),
                0,
                Collections.emptyList(),
                contentType
        );
    }

    private void appendSection(
            List<DiscoveryItem> target,
            String title,
            List<DiscoveryItem> items
    ) {
        if (items.isEmpty()) {
            return;
        }
        target.add(DiscoveryItem.section(title));
        target.addAll(items);
    }

    private List<DiscoveryItem> limit(List<DiscoveryItem> items, int count) {
        if (items.size() <= count) {
            return items;
        }
        return new ArrayList<>(items.subList(0, count));
    }

    public List<Podcast> loadFavoritePodcasts(String accessToken)
            throws IOException, JSONException {
        Map<String, String> headers = authorizationHeaders(accessToken);
        long userId = loadCurrentUserId(headers);
        JSONObject response = httpClient.get(
                API_BASE_URL + "/users/" + userId + "/likes/albums?rich=true",
                headers
        );

        JSONArray likes = response.optJSONArray("result");
        if (likes == null) {
            return Collections.emptyList();
        }

        List<Podcast> podcasts = new ArrayList<>();
        for (int index = 0; index < likes.length(); index++) {
            JSONObject like = likes.optJSONObject(index);
            if (like == null) {
                continue;
            }

            JSONObject album = like.optJSONObject("album");
            if (album == null) {
                album = like;
            }

            String type = album.optString("type", "");
            String metaType = album.optString("metaType", "");
            String albumType = album.optString("albumType", "");
            Podcast.ContentType contentType;
            if ("audiobook".equals(type)
                    || "audiobook".equals(metaType)
                    || "audiobook".equals(albumType)) {
                contentType = Podcast.ContentType.AUDIOBOOK;
            } else if ("podcast".equals(type) || "podcast".equals(metaType)) {
                contentType = Podcast.ContentType.PODCAST;
            } else {
                continue;
            }

            long id = album.optLong("id", 0);
            if (id == 0) {
                continue;
            }

            String description = firstNotEmpty(
                    album.optString("shortDescription", ""),
                    like.optString("shortDescription", ""),
                    album.optString("description", ""),
                    like.optString("description", ""),
                    contentType == Podcast.ContentType.AUDIOBOOK
                            ? "Аудиокнига Яндекс Музыки"
                            : "Подкаст Яндекс Музыки"
            );

            podcasts.add(new Podcast(
                    id,
                    album.optString("title", "Без названия"),
                    description,
                    album.optString("coverUri", null),
                    parseInstant(like.optString("timestamp", null)),
                    album.optInt("trackCount", 0),
                    0,
                    Collections.emptyList(),
                    contentType
            ));
        }
        return podcasts;
    }

    public UserProfile loadUserProfile(String accessToken) throws IOException, JSONException {
        Map<String, String> headers = authorizationHeaders(accessToken);
        JSONObject account = loadCurrentAccount(headers);
        String login = firstNotEmpty(
                account.optString("login", ""),
                account.optString("displayName", ""),
                "Яндекс"
        );
        String avatarUrl = null;
        try {
            JSONObject profile = httpClient.get(
                    "https://login.yandex.ru/info?format=json",
                    headers
            );
            String avatarId = profile.optString("default_avatar_id", "");
            if (!avatarId.isEmpty()) {
                avatarUrl = "https://avatars.yandex.net/get-yapic/"
                        + avatarId + "/islands-200";
            }
        } catch (IOException | JSONException ignored) {
            // The avatar is optional; account/status already supplied the login.
        }
        return new UserProfile(login, avatarUrl);
    }

    public boolean setPodcastFavorite(
            String accessToken,
            long podcastId,
            boolean favorite
    ) throws IOException, JSONException {
        Map<String, String> headers = authorizationHeaders(accessToken);
        long userId = loadCurrentUserId(headers);
        Map<String, String> form = new HashMap<>();
        form.put("album-ids", Long.toString(podcastId));
        JSONObject response = httpClient.postForm(
                API_BASE_URL + "/users/" + userId + "/likes/albums/"
                        + (favorite ? "add-multiple" : "remove"),
                form,
                headers
        );
        return "ok".equalsIgnoreCase(response.optString("result", ""));
    }

    public List<Episode> loadEpisodes(
            String accessToken,
            long podcastId,
            boolean audiobook
    ) throws IOException, JSONException {
        JSONObject response = httpClient.get(
                PROGRESS_API_BASE_URL + "/albums/" + podcastId + "/with-tracks"
                        + "?resumeStream=true&richTracks=true&withListeningFinished=true",
                authorizationHeaders(accessToken)
        );
        JSONObject album = response.optJSONObject("result");
        if (album == null) {
            return Collections.emptyList();
        }

        JSONArray volumes = album.optJSONArray("volumes");
        if (volumes == null) {
            return Collections.emptyList();
        }

        boolean albumIsAudiobook = audiobook
                || "audiobook".equals(album.optString("type", ""))
                || "audiobook".equals(album.optString("metaType", ""));
        List<Episode> episodes = new ArrayList<>();
        int sequenceIndex = 0;
        for (int volumeIndex = 0; volumeIndex < volumes.length(); volumeIndex++) {
            JSONArray tracks = volumes.optJSONArray(volumeIndex);
            if (tracks == null) {
                continue;
            }
            for (int trackIndex = 0; trackIndex < tracks.length(); trackIndex++) {
                JSONObject track = tracks.optJSONObject(trackIndex);
                if (track == null || !isPlayableEpisode(track, albumIsAudiobook)) {
                    continue;
                }

                String id = track.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                Episode episode = new Episode(
                        id,
                        podcastId,
                        track.optString("title", "Без названия"),
                        firstNotEmpty(
                                track.optString("description", ""),
                                track.optString("shortDescription", "")
                        ),
                        albumIsAudiobook
                                ? LocalDate.of(1970, 1, 1).plusDays(sequenceIndex++)
                                : parsePublicationDate(track),
                        track.optLong("durationMs", 0),
                        true,
                        volumeIndex,
                        null
                );
                JSONObject streamProgress = track.optJSONObject("streamProgress");
                if (streamProgress != null) {
                    episode.setListened(
                            streamProgress.optBoolean("everFinished", false)
                                    || streamProgress.optBoolean("hasEverFinished", false)
                    );
                    episode.setPlaybackPositionMs(Math.round(
                            Math.max(0, streamProgress.optDouble("endPositionSec", 0)) * 1000
                    ));
                }
                episodes.add(episode);
            }
        }
        if (volumes.length() <= 1) {
            return episodes;
        }
        return albumIsAudiobook
                ? assignBookParts(episodes)
                : assignChronologicalSeasons(episodes);
    }

    private List<Episode> assignBookParts(List<Episode> episodes) {
        List<Episode> result = new ArrayList<>();
        for (Episode episode : episodes) {
            Episode categorizedEpisode = new Episode(
                    episode.getId(),
                    episode.getPodcastId(),
                    episode.getTitle(),
                    episode.getDescription(),
                    episode.getPublicationDate(),
                    episode.getDurationMs(),
                    episode.isSeen(),
                    episode.getCategoryIndex(),
                    "Часть " + (episode.getCategoryIndex() + 1)
            );
            categorizedEpisode.setListened(episode.isListened());
            categorizedEpisode.setPlaybackPositionMs(episode.getPlaybackPositionMs());
            result.add(categorizedEpisode);
        }
        return result;
    }

    private List<Episode> assignChronologicalSeasons(List<Episode> episodes) {
        Map<Integer, LocalDate> oldestDates = new HashMap<>();
        for (Episode episode : episodes) {
            LocalDate knownOldest = oldestDates.get(episode.getCategoryIndex());
            if (knownOldest == null || episode.getPublicationDate().isBefore(knownOldest)) {
                oldestDates.put(episode.getCategoryIndex(), episode.getPublicationDate());
            }
        }

        List<Integer> sourceIndexes = new ArrayList<>(oldestDates.keySet());
        sourceIndexes.sort((left, right) -> {
            int dateComparison = oldestDates.get(left).compareTo(oldestDates.get(right));
            return dateComparison != 0 ? dateComparison : Integer.compare(left, right);
        });

        Map<Integer, Integer> seasonNumbers = new HashMap<>();
        for (int index = 0; index < sourceIndexes.size(); index++) {
            seasonNumbers.put(sourceIndexes.get(index), index + 1);
        }

        List<Episode> result = new ArrayList<>();
        for (Episode episode : episodes) {
            int seasonNumber = seasonNumbers.get(episode.getCategoryIndex());
            Episode categorizedEpisode = new Episode(
                    episode.getId(),
                    episode.getPodcastId(),
                    episode.getTitle(),
                    episode.getDescription(),
                    episode.getPublicationDate(),
                    episode.getDurationMs(),
                    episode.isSeen(),
                    seasonNumber - 1,
                    "Сезон " + seasonNumber
            );
            categorizedEpisode.setListened(episode.isListened());
            categorizedEpisode.setPlaybackPositionMs(episode.getPlaybackPositionMs());
            result.add(categorizedEpisode);
        }
        return result;
    }

    private long loadCurrentUserId(Map<String, String> headers) throws IOException, JSONException {
        JSONObject account = loadCurrentAccount(headers);
        if (account.optLong("uid", 0) == 0) {
            throw new JSONException("Yandex account UID is missing");
        }
        return account.getLong("uid");
    }

    private JSONObject loadCurrentAccount(Map<String, String> headers)
            throws IOException, JSONException {
        JSONObject response = httpClient.get(API_BASE_URL + "/account/status", headers);
        JSONObject result = response.optJSONObject("result");
        JSONObject account = result == null ? null : result.optJSONObject("account");
        if (account == null) {
            throw new JSONException("Yandex account is missing");
        }
        return account;
    }

    private Map<String, String> authorizationHeaders(String accessToken) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "OAuth " + accessToken);
        return headers;
    }

    private boolean isPlayableEpisode(JSONObject track, boolean audiobook) {
        String type = track.optString("type", "");
        if (type.isEmpty() || "podcast-episode".equals(type)) {
            return true;
        }
        if (!audiobook) {
            return false;
        }
        return "audiobook".equals(type)
                || "audiobook-chapter".equals(type)
                || "book-chapter".equals(type)
                || track.optLong("durationMs", 0) > 0;
    }

    private LocalDate parsePublicationDate(JSONObject track) {
        String value = track.optString("pubDate", "");
        if (!value.isEmpty()) {
            try {
                return LocalDate.parse(value);
            } catch (Exception ignored) {
                // Fall through to other date formats.
            }
        }

        value = track.optString("releaseDate", "");
        if (!value.isEmpty()) {
            try {
                return OffsetDateTime.parse(value).toLocalDate();
            } catch (Exception ignored) {
                try {
                    return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
                } catch (Exception anotherIgnored) {
                    // Use a stable old date when the API omitted the publication date.
                }
            }
        }
        return LocalDate.of(1970, 1, 1);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (Exception anotherIgnored) {
                return null;
            }
        }
    }

    private String firstNotEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
