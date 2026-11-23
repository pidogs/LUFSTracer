package com.pidog.lufstracer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson

class AudioDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val gson = Gson()

    companion object {
        private const val DATABASE_NAME = "lufs_tracer_cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "file_analysis_cache"

        private const val COLUMN_HASH = "file_hash"
        private const val COLUMN_FILE_NAME = "file_name"
        private const val COLUMN_FILE_URI = "file_uri"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_AUDIO_INFO = "audio_info_json"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_HASH TEXT PRIMARY KEY,
                $COLUMN_FILE_NAME TEXT NOT NULL,
                $COLUMN_FILE_URI TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_AUDIO_INFO TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun getCachedResult(hash: String): DecodedAudioInfo? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_AUDIO_INFO),
            "$COLUMN_HASH = ?",
            arrayOf(hash),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(it.getColumnIndexOrThrow(COLUMN_AUDIO_INFO))
                try {
                    gson.fromJson(json, DecodedAudioInfo::class.java)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            } else {
                null
            }
        }
    }

    fun getCachedResultByUri(uri: String): DecodedAudioInfo? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_AUDIO_INFO),
            "$COLUMN_FILE_URI = ?",
            arrayOf(uri),
            null, null, "$COLUMN_TIMESTAMP DESC", "1"
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(it.getColumnIndexOrThrow(COLUMN_AUDIO_INFO))
                try {
                    gson.fromJson(json, DecodedAudioInfo::class.java)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            } else {
                null
            }
        }
    }

    fun saveResult(hash: String, fileName: String, fileUri: String, info: DecodedAudioInfo) {
        val db = writableDatabase
        val json = gson.toJson(info)
        val values = ContentValues().apply {
            put(COLUMN_HASH, hash)
            put(COLUMN_FILE_NAME, fileName)
            put(COLUMN_FILE_URI, fileUri)
            put(COLUMN_TIMESTAMP, System.currentTimeMillis())
            put(COLUMN_AUDIO_INFO, json)
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_NAME, null, null)
    }
}
