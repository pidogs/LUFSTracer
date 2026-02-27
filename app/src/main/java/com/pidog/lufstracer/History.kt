package com.pidog.lufstracer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

// Represents a full saved session
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val fileUri: String, // Store the URI or path so you know what file it was
    val timestamp: Long = System.currentTimeMillis(),
    val metrics: LufsMetrics
)

class HistoryManager(private val context: Context) {

    private val gson = Gson()
    private val historyFile = File(context.filesDir, "lufs_history.json")

    /**
     * Loads all history entries from the internal JSON file.
     * Sorted by newest first.
     */
    fun loadHistory(): List<HistoryEntry> {
        if (!historyFile.exists()) {
            return emptyList()
        }

        return try {
            val json = historyFile.readText()
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            val entries: List<HistoryEntry> = gson.fromJson(json, type) ?: emptyList()

            // Return sorted by newest first
            entries.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Saves a new entry to the history file.
     * Keeps a maximum of 50 recent files to prevent the JSON from getting too huge.
     */
    fun saveEntry(
        fileName: String,
        fileUri: String,
        metrics: LufsMetrics
    ) {
        val currentHistory = loadHistory().toMutableList()

        // Remove existing entry if it's the exact same file being re-analyzed
        currentHistory.removeAll { it.fileUri == fileUri }

        val newEntry = HistoryEntry(
            fileName = fileName,
            fileUri = fileUri,
            metrics = metrics
        )

        currentHistory.add(0, newEntry) // Add to top

        // Keep only the 50 most recent items to avoid massive storage usage
        val trimmedHistory = currentHistory.take(50)

        saveToFile(trimmedHistory)
    }

    /**
     * Deletes a specific entry by its ID.
     */
    fun deleteEntry(id: String) {
        val currentHistory = loadHistory().toMutableList()
        currentHistory.removeAll { it.id == id }
        saveToFile(currentHistory)
    }

    /**
     * Clears all history.
     */
    fun clearHistory() {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }

    private fun saveToFile(entries: List<HistoryEntry>) {
        try {
            val json = gson.toJson(entries)
            historyFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}