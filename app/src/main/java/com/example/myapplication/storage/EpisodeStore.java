package com.example.myapplication.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.myapplication.model.Episode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EpisodeStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "podcast_history.db";
    private static final int DATABASE_VERSION = 5;

    public EpisodeStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE podcast_sync ("
                        + "podcast_id INTEGER PRIMARY KEY, "
                        + "initialized_at INTEGER NOT NULL, "
                        + "last_sync_at INTEGER NOT NULL, "
                        + "known_episode_count INTEGER NOT NULL DEFAULT -1, "
                        + "unseen_count INTEGER NOT NULL DEFAULT 0, "
                        + "episodes_loaded INTEGER NOT NULL DEFAULT 0, "
                        + "last_opened_episode_id TEXT, "
                        + "last_opened_at INTEGER NOT NULL DEFAULT 0)"
        );
        database.execSQL(
                "CREATE TABLE episodes ("
                        + "podcast_id INTEGER NOT NULL, "
                        + "episode_id TEXT NOT NULL, "
                        + "title TEXT NOT NULL, "
                        + "description TEXT, "
                        + "publication_date TEXT NOT NULL, "
                        + "duration_ms INTEGER NOT NULL, "
                        + "category_index INTEGER NOT NULL DEFAULT 0, "
                        + "category_title TEXT, "
                        + "is_new INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (podcast_id, episode_id))"
        );
        database.execSQL(
                "CREATE INDEX episodes_podcast_date "
                        + "ON episodes(podcast_id, publication_date DESC)"
        );
        createPlaybackTable(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            database.execSQL(
                    "ALTER TABLE podcast_sync "
                            + "ADD COLUMN known_episode_count INTEGER NOT NULL DEFAULT -1"
            );
            database.execSQL(
                    "ALTER TABLE podcast_sync "
                            + "ADD COLUMN unseen_count INTEGER NOT NULL DEFAULT 0"
            );
            // Version 1 always downloaded episode lists, so those rows are initialized.
            database.execSQL(
                    "ALTER TABLE podcast_sync "
                            + "ADD COLUMN episodes_loaded INTEGER NOT NULL DEFAULT 1"
            );
            database.execSQL(
                    "ALTER TABLE podcast_sync ADD COLUMN last_opened_episode_id TEXT"
            );
        }
        if (oldVersion < 3) {
            database.execSQL(
                    "ALTER TABLE episodes "
                            + "ADD COLUMN category_index INTEGER NOT NULL DEFAULT 0"
            );
            database.execSQL("ALTER TABLE episodes ADD COLUMN category_title TEXT");
        }
        if (oldVersion < 4) {
            database.execSQL(
                    "ALTER TABLE podcast_sync "
                            + "ADD COLUMN last_opened_at INTEGER NOT NULL DEFAULT 0"
            );
            database.execSQL(
                    "UPDATE podcast_sync SET last_opened_at = last_sync_at "
                            + "WHERE last_opened_episode_id IS NOT NULL"
            );
        }
        if (oldVersion < 5) {
            createPlaybackTable(database);
        }
    }

    /**
     * Updates the lightweight state shown on the main screen. The first count is
     * only a baseline, so a fresh install or cleared app data never labels the
     * entire archive as new.
     */
    public int synchronizePodcastCount(long podcastId, int currentCount) {
        int safeCurrentCount = Math.max(0, currentCount);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            SyncState state = loadSyncState(database, podcastId);
            long now = System.currentTimeMillis();
            int unseenCount;

            if (state == null || state.knownEpisodeCount < 0) {
                unseenCount = 0;
            } else {
                int added = Math.max(0, safeCurrentCount - state.knownEpisodeCount);
                unseenCount = Math.min(safeCurrentCount, state.unseenCount + added);
            }

            ContentValues values = baseSyncValues(podcastId, state, now);
            values.put("known_episode_count", safeCurrentCount);
            values.put("unseen_count", unseenCount);
            if (state == null) {
                values.put("episodes_loaded", 0);
            }
            database.insertWithOnConflict(
                    "podcast_sync",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            database.setTransactionSuccessful();
            return unseenCount;
        } finally {
            database.endTransaction();
        }
    }

    /** Saves one podcast's full server snapshot when its detail page is opened. */
    public List<Episode> synchronize(long podcastId, List<Episode> serverEpisodes) {
        List<Episode> sortedEpisodes = uniqueAndSort(serverEpisodes);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            for (Episode episode : serverEpisodes) {
                mergeServerPlaybackProgress(database, episode);
            }
            SyncState state = loadSyncState(database, podcastId);
            boolean episodesWereLoaded = state != null && state.episodesLoaded;
            int summaryUnseenCount = state == null ? 0 : state.unseenCount;
            Map<String, Boolean> oldNewState = loadNewState(database, podcastId);
            Map<String, Boolean> nextNewState = new HashMap<>();

            for (int index = 0; index < sortedEpisodes.size(); index++) {
                Episode episode = sortedEpisodes.get(index);
                boolean isNew;
                if (oldNewState.containsKey(episode.getId())) {
                    isNew = Boolean.TRUE.equals(oldNewState.get(episode.getId()));
                } else if (episodesWereLoaded) {
                    isNew = true;
                } else {
                    isNew = index >= sortedEpisodes.size() - summaryUnseenCount;
                }
                nextNewState.put(episode.getId(), isNew);
            }

            database.delete(
                    "episodes",
                    "podcast_id = ?",
                    new String[]{String.valueOf(podcastId)}
            );
            for (Episode episode : sortedEpisodes) {
                ContentValues values = episodeValues(episode);
                values.put("is_new", Boolean.TRUE.equals(nextNewState.get(episode.getId())) ? 1 : 0);
                database.insert("episodes", null, values);
            }

            int actualUnseenCount = 0;
            for (Boolean isNew : nextNewState.values()) {
                if (Boolean.TRUE.equals(isNew)) {
                    actualUnseenCount++;
                }
            }

            long now = System.currentTimeMillis();
            ContentValues syncValues = baseSyncValues(podcastId, state, now);
            syncValues.put("known_episode_count", sortedEpisodes.size());
            syncValues.put("unseen_count", actualUnseenCount);
            syncValues.put("episodes_loaded", 1);
            database.insertWithOnConflict(
                    "podcast_sync",
                    null,
                    syncValues,
                    SQLiteDatabase.CONFLICT_REPLACE
            );

            database.setTransactionSuccessful();
            return applyNewState(sortedEpisodes, nextNewState);
        } finally {
            database.endTransaction();
        }
    }

    public List<Episode> getEpisodes(long podcastId) {
        SQLiteDatabase database = getReadableDatabase();
        List<Episode> episodes = new ArrayList<>();
        try (Cursor cursor = database.query(
                "episodes",
                new String[]{
                        "episode_id", "title", "description", "publication_date",
                        "duration_ms", "is_new", "category_index", "category_title"
                },
                "podcast_id = ?",
                new String[]{String.valueOf(podcastId)},
                null,
                null,
                "category_index ASC, publication_date ASC, episode_id ASC"
        )) {
            while (cursor.moveToNext()) {
                episodes.add(new Episode(
                        cursor.getString(0),
                        podcastId,
                        cursor.getString(1),
                        cursor.isNull(2) ? "" : cursor.getString(2),
                        parseDate(cursor.getString(3)),
                        cursor.getLong(4),
                        cursor.getInt(5) == 0,
                        cursor.getInt(6),
                        cursor.isNull(7) ? null : cursor.getString(7)
                ));
            }
        }
        return normalizeAndSortCategories(episodes);
    }

    public void markAllSeen(long podcastId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues episodeValues = new ContentValues();
            episodeValues.put("is_new", 0);
            database.update(
                    "episodes", episodeValues, "podcast_id = ?",
                    new String[]{String.valueOf(podcastId)}
            );
            ContentValues syncValues = new ContentValues();
            syncValues.put("unseen_count", 0);
            database.update(
                    "podcast_sync", syncValues, "podcast_id = ?",
                    new String[]{String.valueOf(podcastId)}
            );
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public void setLastOpenedEpisode(long podcastId, String episodeId) {
        SQLiteDatabase database = getWritableDatabase();
        SyncState state = loadSyncState(database, podcastId);
        ContentValues values = baseSyncValues(podcastId, state, System.currentTimeMillis());
        values.put("last_opened_episode_id", episodeId);
        values.put("last_opened_at", System.currentTimeMillis());
        if (state == null) {
            values.put("known_episode_count", -1);
            values.put("unseen_count", 0);
            values.put("episodes_loaded", 0);
        }
        database.insertWithOnConflict(
                "podcast_sync", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public String getLastOpenedEpisodeId(long podcastId) {
        SyncState state = loadSyncState(getReadableDatabase(), podcastId);
        return state == null ? null : state.lastOpenedEpisodeId;
    }

    public long getLastOpenedAt(long podcastId) {
        SyncState state = loadSyncState(getReadableDatabase(), podcastId);
        return state == null ? 0 : state.lastOpenedAt;
    }

    public void savePlaybackProgress(
            long podcastId,
            String episodeId,
            long positionMs,
            long durationMs,
            boolean completed,
            float speed
    ) {
        if (episodeId == null || episodeId.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("podcast_id", podcastId);
        values.put("episode_id", episodeId);
        values.put("position_ms", Math.max(0, positionMs));
        values.put("duration_ms", Math.max(0, durationMs));
        values.put("completed", completed ? 1 : 0);
        values.put("last_played_at", System.currentTimeMillis());
        values.put("playback_speed", Math.max(0.5f, speed));
        getWritableDatabase().insertWithOnConflict(
                "episode_playback", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    private void mergeServerPlaybackProgress(SQLiteDatabase database, Episode episode) {
        long serverPositionMs = episode.getPlaybackPositionMs();
        if (!episode.isListened() && serverPositionMs <= 0) {
            return;
        }

        PlaybackProgress local = loadPlaybackProgress(
                database, episode.getPodcastId(), episode.getId()
        );
        boolean completed = episode.isListened() || local.isCompleted();
        long durationMs = Math.max(episode.getDurationMs(), local.getDurationMs());
        long positionMs = Math.max(serverPositionMs, local.getPositionMs());
        if (durationMs > 0) {
            positionMs = Math.min(positionMs, durationMs);
        }

        ContentValues values = new ContentValues();
        values.put("podcast_id", episode.getPodcastId());
        values.put("episode_id", episode.getId());
        values.put("position_ms", positionMs);
        values.put("duration_ms", durationMs);
        values.put("completed", completed ? 1 : 0);
        values.put("last_played_at", local.getLastPlayedAt());
        values.put("playback_speed", local.getSpeed());
        database.insertWithOnConflict(
                "episode_playback", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    private PlaybackProgress loadPlaybackProgress(
            SQLiteDatabase database,
            long podcastId,
            String episodeId
    ) {
        try (Cursor cursor = database.query(
                "episode_playback",
                new String[]{
                        "position_ms", "duration_ms", "completed",
                        "last_played_at", "playback_speed"
                },
                "podcast_id = ? AND episode_id = ?",
                new String[]{String.valueOf(podcastId), episodeId},
                null,
                null,
                null,
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return PlaybackProgress.empty();
            }
            return new PlaybackProgress(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getInt(2) != 0,
                    cursor.getLong(3),
                    cursor.getFloat(4)
            );
        }
    }

    public PlaybackProgress getPlaybackProgress(long podcastId, String episodeId) {
        if (episodeId == null) {
            return PlaybackProgress.empty();
        }
        try (Cursor cursor = getReadableDatabase().query(
                "episode_playback",
                new String[]{
                        "position_ms", "duration_ms", "completed",
                        "last_played_at", "playback_speed"
                },
                "podcast_id = ? AND episode_id = ?",
                new String[]{String.valueOf(podcastId), episodeId},
                null,
                null,
                null,
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return PlaybackProgress.empty();
            }
            return new PlaybackProgress(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getInt(2) != 0,
                    cursor.getLong(3),
                    cursor.getFloat(4)
            );
        }
    }

    public Map<String, PlaybackProgress> getPlaybackProgress(long podcastId) {
        Map<String, PlaybackProgress> progressByEpisode = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().query(
                "episode_playback",
                new String[]{
                        "episode_id", "position_ms", "duration_ms", "completed",
                        "last_played_at", "playback_speed"
                },
                "podcast_id = ?",
                new String[]{String.valueOf(podcastId)},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                progressByEpisode.put(cursor.getString(0), new PlaybackProgress(
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getInt(3) != 0,
                        cursor.getLong(4),
                        cursor.getFloat(5)
                ));
            }
        }
        return progressByEpisode;
    }

    /** Returns the latest unfinished episode so the detail screen can scroll to it. */
    public String getResumeEpisodeId(long podcastId) {
        try (Cursor cursor = getReadableDatabase().query(
                "episode_playback",
                new String[]{"episode_id"},
                "podcast_id = ? AND completed = 0 AND position_ms > 0",
                new String[]{String.valueOf(podcastId)},
                null,
                null,
                "last_played_at DESC",
                "1"
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return getLastOpenedEpisodeId(podcastId);
    }

    public long getLastPlayedAt(long podcastId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT MAX(last_played_at) FROM episode_playback WHERE podcast_id = ?",
                new String[]{String.valueOf(podcastId)}
        )) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return getLastOpenedAt(podcastId);
    }

    public void clearAll() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("episode_playback", null, null);
            database.delete("episodes", null, null);
            database.delete("podcast_sync", null, null);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void createPlaybackTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS episode_playback ("
                        + "podcast_id INTEGER NOT NULL, "
                        + "episode_id TEXT NOT NULL, "
                        + "position_ms INTEGER NOT NULL DEFAULT 0, "
                        + "duration_ms INTEGER NOT NULL DEFAULT 0, "
                        + "completed INTEGER NOT NULL DEFAULT 0, "
                        + "last_played_at INTEGER NOT NULL DEFAULT 0, "
                        + "playback_speed REAL NOT NULL DEFAULT 1.0, "
                        + "PRIMARY KEY (podcast_id, episode_id))"
        );
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS playback_podcast_time "
                        + "ON episode_playback(podcast_id, last_played_at DESC)"
        );
    }

    public static final class PlaybackProgress {

        private final long positionMs;
        private final long durationMs;
        private final boolean completed;
        private final long lastPlayedAt;
        private final float speed;

        PlaybackProgress(
                long positionMs,
                long durationMs,
                boolean completed,
                long lastPlayedAt,
                float speed
        ) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.completed = completed;
            this.lastPlayedAt = lastPlayedAt;
            this.speed = speed;
        }

        static PlaybackProgress empty() {
            return new PlaybackProgress(0, 0, false, 0, 1f);
        }

        public long getPositionMs() {
            return positionMs;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean isCompleted() {
            return completed;
        }

        public long getLastPlayedAt() {
            return lastPlayedAt;
        }

        public float getSpeed() {
            return speed;
        }
    }

    private SyncState loadSyncState(SQLiteDatabase database, long podcastId) {
        try (Cursor cursor = database.query(
                "podcast_sync",
                new String[]{
                        "initialized_at", "last_sync_at", "known_episode_count",
                        "unseen_count", "episodes_loaded", "last_opened_episode_id",
                        "last_opened_at"
                },
                "podcast_id = ?",
                new String[]{String.valueOf(podcastId)},
                null,
                null,
                null,
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new SyncState(
                    cursor.getLong(0),
                    cursor.getInt(2),
                    cursor.getInt(3),
                    cursor.getInt(4) != 0,
                    cursor.isNull(5) ? null : cursor.getString(5),
                    cursor.getLong(6)
            );
        }
    }

    private ContentValues baseSyncValues(long podcastId, SyncState state, long now) {
        ContentValues values = new ContentValues();
        values.put("podcast_id", podcastId);
        values.put("initialized_at", state == null ? now : state.initializedAt);
        values.put("last_sync_at", now);
        if (state != null) {
            values.put("known_episode_count", state.knownEpisodeCount);
            values.put("unseen_count", state.unseenCount);
            values.put("episodes_loaded", state.episodesLoaded ? 1 : 0);
            values.put("last_opened_episode_id", state.lastOpenedEpisodeId);
            values.put("last_opened_at", state.lastOpenedAt);
        }
        return values;
    }

    private Map<String, Boolean> loadNewState(SQLiteDatabase database, long podcastId) {
        Map<String, Boolean> result = new HashMap<>();
        try (Cursor cursor = database.query(
                "episodes",
                new String[]{"episode_id", "is_new"},
                "podcast_id = ?",
                new String[]{String.valueOf(podcastId)},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getInt(1) != 0);
            }
        }
        return result;
    }

    private ContentValues episodeValues(Episode episode) {
        ContentValues values = new ContentValues();
        values.put("podcast_id", episode.getPodcastId());
        values.put("episode_id", episode.getId());
        values.put("title", episode.getTitle());
        values.put("description", episode.getDescription());
        values.put("publication_date", episode.getPublicationDate().toString());
        values.put("duration_ms", episode.getDurationMs());
        values.put("category_index", episode.getCategoryIndex());
        values.put("category_title", episode.getCategoryTitle());
        return values;
    }

    private List<Episode> uniqueAndSort(List<Episode> episodes) {
        List<Episode> result = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        for (Episode episode : episodes) {
            if (addedIds.add(episode.getId())) {
                result.add(episode);
            }
        }
        return normalizeAndSortCategories(result);
    }

    private List<Episode> normalizeAndSortCategories(List<Episode> episodes) {
        Map<Integer, LocalDate> oldestDates = new HashMap<>();
        boolean hasCategories = false;
        boolean generatedSeasonNames = true;
        for (Episode episode : episodes) {
            String title = episode.getCategoryTitle();
            if (title != null) {
                hasCategories = true;
                generatedSeasonNames &= title.matches("Сезон \\d+");
            }
            LocalDate oldestDate = oldestDates.get(episode.getCategoryIndex());
            if (oldestDate == null || episode.getPublicationDate().isBefore(oldestDate)) {
                oldestDates.put(episode.getCategoryIndex(), episode.getPublicationDate());
            }
        }

        List<Integer> categoryIndexes = new ArrayList<>(oldestDates.keySet());
        categoryIndexes.sort((left, right) -> {
            int dateComparison = oldestDates.get(left).compareTo(oldestDates.get(right));
            return dateComparison != 0 ? dateComparison : Integer.compare(left, right);
        });

        Map<Integer, Integer> chronologicalIndexes = new HashMap<>();
        for (int index = 0; index < categoryIndexes.size(); index++) {
            chronologicalIndexes.put(categoryIndexes.get(index), index);
        }

        List<Episode> normalized = new ArrayList<>();
        for (Episode episode : episodes) {
            int chronologicalIndex = chronologicalIndexes.get(episode.getCategoryIndex());
            String categoryTitle = episode.getCategoryTitle();
            if (hasCategories && generatedSeasonNames) {
                categoryTitle = "Сезон " + (chronologicalIndex + 1);
            }
            normalized.add(new Episode(
                    episode.getId(),
                    episode.getPodcastId(),
                    episode.getTitle(),
                    episode.getDescription(),
                    episode.getPublicationDate(),
                    episode.getDurationMs(),
                    episode.isSeen(),
                    chronologicalIndex,
                    categoryTitle
            ));
        }
        normalized.sort(
                Comparator.comparingInt(Episode::getCategoryIndex)
                        .thenComparing(Episode::getPublicationDate)
                        .thenComparing(Episode::getId)
        );
        return normalized;
    }

    private List<Episode> applyNewState(
            List<Episode> serverEpisodes,
            Map<String, Boolean> newState
    ) {
        List<Episode> result = new ArrayList<>();
        for (Episode episode : serverEpisodes) {
            result.add(new Episode(
                    episode.getId(), episode.getPodcastId(), episode.getTitle(),
                    episode.getDescription(), episode.getPublicationDate(), episode.getDurationMs(),
                    !Boolean.TRUE.equals(newState.get(episode.getId())),
                    episode.getCategoryIndex(), episode.getCategoryTitle()
            ));
        }
        return result;
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return LocalDate.of(1970, 1, 1);
        }
    }

    private static final class SyncState {

        final long initializedAt;
        final int knownEpisodeCount;
        final int unseenCount;
        final boolean episodesLoaded;
        final String lastOpenedEpisodeId;
        final long lastOpenedAt;

        SyncState(
                long initializedAt,
                int knownEpisodeCount,
                int unseenCount,
                boolean episodesLoaded,
                String lastOpenedEpisodeId,
                long lastOpenedAt
        ) {
            this.initializedAt = initializedAt;
            this.knownEpisodeCount = knownEpisodeCount;
            this.unseenCount = unseenCount;
            this.episodesLoaded = episodesLoaded;
            this.lastOpenedEpisodeId = lastOpenedEpisodeId;
            this.lastOpenedAt = lastOpenedAt;
        }
    }
}
