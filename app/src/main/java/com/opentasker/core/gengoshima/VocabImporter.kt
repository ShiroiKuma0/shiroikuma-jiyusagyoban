package com.opentasker.core.gengoshima

import android.content.Context
import androidx.room.withTransaction
import com.opentasker.app.OpenTaskerApp_NoHilt
import kotlinx.serialization.json.Json

/**
 * [VocabImport] against the live database: files a parsed paste in one transaction, remembers what
 * it created so the last import can be taken back, and takes it back.
 */
object VocabImporter {

    private const val PREFS = "gengoshima_import"
    private const val KEY_LAST = "last"
    private val json = Json { ignoreUnknownKeys = true }

    /** The plan of [parsed] against what the database holds now. */
    suspend fun plan(parsed: VocabImport.Parsed, targets: Map<Int, Long> = emptyMap()): VocabImport.Plan {
        val dao = OpenTaskerApp_NoHilt.db.gengoshimaDao()
        return VocabImport.plan(parsed, dao.islands(), dao.allVocab(), dao.allSentences(), targets)
    }

    /** File it. The plan is made again inside the transaction, so it is of the rows as they are now. */
    suspend fun file(context: Context, parsed: VocabImport.Parsed, targets: Map<Int, Long> = emptyMap()): VocabImport.Receipt {
        val db = OpenTaskerApp_NoHilt.db
        val dao = db.gengoshimaDao()
        val receipt = db.withTransaction {
            VocabImport.apply(dao, VocabImport.plan(parsed, dao.islands(), dao.allVocab(), dao.allSentences(), targets))
        }
        if (!receipt.empty) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST, json.encodeToString(VocabImport.Receipt.serializer(), receipt)).apply()
        }
        return receipt
    }

    /** The last import that can still be taken back, or null. */
    fun last(context: Context): VocabImport.Receipt? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
            ?.let { runCatching { json.decodeFromString(VocabImport.Receipt.serializer(), it) }.getOrNull() }
            ?.takeUnless { it.empty }

    /** Take the last import back; returns what was removed, or null when there is none. */
    suspend fun undoLast(context: Context): VocabImport.Receipt? {
        val receipt = last(context) ?: return null
        val db = OpenTaskerApp_NoHilt.db
        val gone = db.withTransaction { VocabImport.undo(db.gengoshimaDao(), receipt) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        return gone
    }
}
