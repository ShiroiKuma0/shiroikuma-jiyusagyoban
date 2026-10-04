package com.opentasker.core.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 言語島 — Mikel Hyperpolyglot's "language islands": 白い熊's own English sentences, grouped into
 * short ordered monologues on one topic, translated into natural spoken Japanese by Claude and voiced
 * by 白い熊 音声 (VOICEVOX No.7) into one OGG per sentence.
 *
 * **The database is authoritative.** The audio tree under `%Gengoshima_Dir` is a VIEW of these rows:
 * a sentence knows the path its file is at now and the hash of what that file says, so a reorder or a
 * rename only moves files, and nothing is re-voiced unless the words, the readings or the voice
 * changed. Nothing in the tree is ever the only copy of anything authored — the English, the
 * Japanese and every edit live here, and travel in the `gengoshima` backup category.
 */
@Entity(tableName = "gengoshima_islands", indices = [Index(value = ["position"])])
data class GengoshimaIslandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1-based order of the islands; the `{no}` of the directory name. */
    val position: Int,
    val nameEn: String,
    /** Blank until the translator has named it; the directory then takes the Japanese name too. */
    val nameJa: String = "",
    /**
     * The register the island is spoken in — "casual, to a friend", "polite, to a stranger" — sent
     * to the translator with every call, so one island does not drift between です and だ.
     */
    val register: String = "",
    /** new / shadowed / recalled / rotating — the island's place in Mikel's routine. */
    val status: String = "new",
    // Spaced island rotation (SM-2 style); filled in by the player, not by entry.
    val intervalDays: Double = 0.0,
    val ease: Double = 2.5,
    val nextReview: Long? = null,
    val lastReview: Long? = null,
    /** The directory name this island's files are under NOW, so 整理 can rename rather than rebuild. */
    val dirName: String? = null,
    val createdAt: Long,
    /**
     * The island's permanent identity, never reused — what 白い熊 暗記's decks are keyed by, so a
     * rename renames the decks instead of making new ones (v34). Existing rows were given one by the
     * migration.
     */
    @ColumnInfo(defaultValue = "") val uuid: String = java.util.UUID.randomUUID().toString(),
)

@Entity(
    tableName = "gengoshima_sentences",
    indices = [Index(value = ["islandId", "position"]), Index(value = ["state"])],
)
data class GengoshimaSentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val islandId: Long,
    /** 1-based order within the island — entry order, which is the order the monologue is spoken in. */
    val position: Int,
    val en: String,
    /** Blank until translated. */
    val ja: String = "",
    /**
     * The translator's word split, as JSON: `[{surface, base, reading, reading_override?, gloss}]`.
     * `reading_override` is 白い熊's own, and is what steers VOICEVOX: the word is sent to it in
     * katakana, which it reads verbatim.
     */
    val tokensJson: String = "",
    /**
     * new → translated → ready, or error. `new` also means "the English changed, translate again";
     * `annotate` means "the Japanese was edited by hand, split it into words again" (then voiced).
     */
    val state: String = STATE_NEW,
    /** True once 白い熊 has edited the Japanese by hand — a re-translation must not overwrite it. */
    val jaEdited: Boolean = false,
    /** Where the OGG is now, or blank if there is none. */
    val audioPath: String = "",
    /** SHA-1 of what the file says: the text sent to 音声 plus every voice parameter. */
    val audioHash: String = "",
    val durationMs: Long = 0,
    /** The last failure, one line, for the screen to show; blank when the sentence is fine. */
    val error: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** Permanent identity; the Anki note carries it as the tag `li::uuid::<uuid>` (v34). */
    @ColumnInfo(defaultValue = "") val uuid: String = java.util.UUID.randomUUID().toString(),
    /** The ENGLISH reading of [en] (音声 lang=en, Kokoro), beside the Japanese one. Blank when none. */
    @ColumnInfo(defaultValue = "") val enAudioPath: String = "",
    @ColumnInfo(defaultValue = "") val enAudioHash: String = "",
    @ColumnInfo(defaultValue = "0") val enDurationMs: Long = 0,
    /**
     * What this sentence looked like when 暗記 last took it — a hash of everything the note carries.
     * A delta sync sends exactly the sentences whose current hash differs. Blank = never synced.
     */
    @ColumnInfo(defaultValue = "") val ankiHash: String = "",
    /** The existing Anki note this sentence was adopted from (「暗記から取り込む」), until synced. */
    @ColumnInfo(defaultValue = "NULL") val ankiNid: Long? = null,
) {
    companion object {
        const val STATE_NEW = "new"
        const val STATE_TRANSLATED = "translated"
        const val STATE_READY = "ready"
        const val STATE_ERROR = "error"
        const val STATE_ANNOTATE = "annotate"
    }
}

/** One listening session — the history, calendar and statistics are built from these. */
@Entity(tableName = "gengoshima_sessions", indices = [Index(value = ["startedAt"])])
data class GengoshimaSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** listen / shadow / recall */
    val mode: String,
    /** The islands the session played, comma-separated ids, in the order played. */
    val islandIds: String,
    val sentencesPlayed: Int = 0,
    val listenedMs: Long = 0,
)

/**
 * Something deleted that 白い熊 暗記 still holds: the next sync tells it, then the row goes. Kept for a
 * sentence or a whole island, by uuid — the row it named no longer exists to ask (v34).
 */
@Entity(tableName = "gengoshima_tombstones")
data class GengoshimaTombstoneEntity(
    @PrimaryKey val uuid: String,
    /** sentence / island */
    val kind: String,
    val deletedAt: Long,
)

/** How often one sentence was played in one session. Feeds per-sentence and per-island progress. */
@Entity(
    tableName = "gengoshima_plays",
    primaryKeys = ["sessionId", "sentenceId"],
    indices = [Index(value = ["sentenceId"])],
)
data class GengoshimaPlayEntity(
    val sessionId: Long,
    val sentenceId: Long,
    val count: Int,
)

/**
 * 言語島's 未分類 inbox (v35): sentences handed in by 白い熊 kxkb after review (walk capture —
 * docs/sister-app-contract-kxkb-gengoshima.md), waiting to be filed into an island.
 *
 * The uuid is the capture uuid kxkb sends, so a retried hand-off is recognised. A row stays after it is
 * filed (`state = filed`) for exactly that reason: "already there" must still be answered `OK`.
 *
 * The target is EITHER an existing island ([islandId]) OR a new one ([newIslandName] + [newIslandRegister]),
 * first as Claude proposed it ([proposed] = 1), then as 白い熊 changed it on the 未分類 page.
 */
@Entity(tableName = "gengoshima_inbox", indices = [Index(value = ["state"])])
data class GengoshimaInboxEntity(
    @PrimaryKey val uuid: String,
    val en: String,
    /** What Whisper heard before review; for reference. */
    val recognized: String,
    val language: String,
    val capturedAt: Long,
    val receivedAt: Long,
    val islandId: Long? = null,
    val newIslandName: String = "",
    val newIslandRegister: String = "",
    /** 1 once Claude's proposal has been stored (so it is asked once, not on every open). */
    val proposed: Int = 0,
    /** pending / filed */
    val state: String = STATE_PENDING,
    /** The sentence row it became, once filed. */
    val sentenceId: Long? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_FILED = "filed"
    }
}

@Dao
interface GengoshimaDao {

    // ── islands ─────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM gengoshima_islands ORDER BY position, id")
    fun observeIslands(): Flow<List<GengoshimaIslandEntity>>

    @Query("SELECT * FROM gengoshima_islands ORDER BY position, id")
    suspend fun islands(): List<GengoshimaIslandEntity>

    @Query("SELECT * FROM gengoshima_islands WHERE id = :id")
    suspend fun island(id: Long): GengoshimaIslandEntity?

    @Query("SELECT COALESCE(MAX(position), 0) FROM gengoshima_islands")
    suspend fun lastIslandPosition(): Int

    @Insert
    suspend fun insertIsland(island: GengoshimaIslandEntity): Long

    @Update
    suspend fun updateIsland(island: GengoshimaIslandEntity)

    // ── sentences ───────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM gengoshima_sentences WHERE islandId = :islandId ORDER BY position, id")
    fun observeSentences(islandId: Long): Flow<List<GengoshimaSentenceEntity>>

    @Query("SELECT * FROM gengoshima_sentences WHERE islandId = :islandId ORDER BY position, id")
    suspend fun sentences(islandId: Long): List<GengoshimaSentenceEntity>

    @Query("SELECT * FROM gengoshima_sentences ORDER BY islandId, position, id")
    suspend fun allSentences(): List<GengoshimaSentenceEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM gengoshima_sentences WHERE islandId = :islandId")
    suspend fun lastSentencePosition(islandId: Long): Int

    /** The duplicate check while typing: every sentence, in any island, containing the text. */
    @Query(
        "SELECT * FROM gengoshima_sentences WHERE en LIKE '%' || :text || '%' " +
            "ORDER BY islandId, position LIMIT :limit",
    )
    fun observeMatches(text: String, limit: Int = 20): Flow<List<GengoshimaSentenceEntity>>

    /** Sentences whose English still has to be (re)translated. */
    @Query("SELECT COUNT(*) FROM gengoshima_sentences WHERE state = 'new'")
    suspend fun pendingTranslation(): Int

    /** Anything the generation runner still has work on: untranslated, unvoiced, or failed. */
    @Query("SELECT COUNT(*) FROM gengoshima_sentences WHERE state != 'ready'")
    suspend fun pendingWork(): Int

    @Insert
    suspend fun insertSentence(sentence: GengoshimaSentenceEntity): Long

    @Update
    suspend fun updateSentence(sentence: GengoshimaSentenceEntity)

    @Query("SELECT * FROM gengoshima_sentences WHERE id = :id")
    suspend fun sentence(id: Long): GengoshimaSentenceEntity?

    // ── sessions ────────────────────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertSession(session: GengoshimaSessionEntity): Long

    @Update
    suspend fun updateSession(session: GengoshimaSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlays(plays: List<GengoshimaPlayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(t: GengoshimaTombstoneEntity)

    @Query("SELECT * FROM gengoshima_tombstones")
    suspend fun tombstones(): List<GengoshimaTombstoneEntity>

    @Query("DELETE FROM gengoshima_tombstones WHERE uuid IN (:uuids)")
    suspend fun clearTombstones(uuids: List<String>)

    // ── inbox (未分類) ─────────────────────────────────────────────────────────────────────────

    /** Store what is new; a uuid already present (pending or filed) is left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(rows: List<GengoshimaInboxEntity>): List<Long>

    @Query("SELECT uuid FROM gengoshima_inbox WHERE uuid IN (:uuids)")
    suspend fun inboxPresent(uuids: List<String>): List<String>

    @Query("SELECT * FROM gengoshima_inbox WHERE state = 'pending' ORDER BY capturedAt, uuid")
    fun observeInbox(): Flow<List<GengoshimaInboxEntity>>

    @Query("SELECT * FROM gengoshima_inbox WHERE state = 'pending' ORDER BY capturedAt, uuid")
    suspend fun inbox(): List<GengoshimaInboxEntity>

    @Query("SELECT COUNT(*) FROM gengoshima_inbox WHERE state = 'pending'")
    fun observeInboxCount(): Flow<Int>

    @Update
    suspend fun updateInbox(rows: List<GengoshimaInboxEntity>)

    @Query("SELECT * FROM gengoshima_sessions ORDER BY startedAt")
    fun observeSessions(): Flow<List<GengoshimaSessionEntity>>

    /** Every play of every sentence, summed over all sessions. */
    @Query("SELECT sentenceId AS sentenceId, SUM(count) AS plays FROM gengoshima_plays GROUP BY sentenceId")
    fun observePlays(): Flow<List<SentencePlays>>

    @Query("SELECT * FROM gengoshima_sentences ORDER BY islandId, position, id")
    fun observeAllSentences(): Flow<List<GengoshimaSentenceEntity>>

    // ── editing ─────────────────────────────────────────────────────────────────────────────────

    @Query("DELETE FROM gengoshima_sentences WHERE id = :id")
    suspend fun deleteSentence(id: Long)

    @Query("DELETE FROM gengoshima_sentences WHERE islandId = :islandId")
    suspend fun deleteSentencesOf(islandId: Long)

    @Query("DELETE FROM gengoshima_islands WHERE id = :id")
    suspend fun deleteIsland(id: Long)

    @Query("SELECT islandId AS islandId, COUNT(*) AS total, SUM(CASE WHEN state = 'ready' THEN 1 ELSE 0 END) AS ready FROM gengoshima_sentences GROUP BY islandId")
    fun observeCounts(): Flow<List<IslandCount>>
}

/** How often one sentence has been played, over every session. */
data class SentencePlays(val sentenceId: Long, val plays: Int)

/** Sentences per island, and how many of them can be played. */
data class IslandCount(val islandId: Long, val total: Int, val ready: Int)
