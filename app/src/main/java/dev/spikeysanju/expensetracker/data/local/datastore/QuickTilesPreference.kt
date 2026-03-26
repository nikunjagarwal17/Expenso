package dev.spikeysanju.expensetracker.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickTilesPreference @Inject constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("quick_tiles_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val QUICK_TILES_KEY = "quick_tiles"
        private val DEFAULT_TILES = listOf("Food", "Travel", "Souvenirs", "General Store")
    }
    
    fun getTiles(): List<String> {
        val tilesJson = prefs.getString(QUICK_TILES_KEY, null)
        return if (tilesJson != null) {
            try {
                // Parse JSON string back to list (using | as separator to avoid issues with tile names containing commas)
                tilesJson.split("|").filter { it.isNotBlank() }
            } catch (e: Exception) {
                DEFAULT_TILES
            }
        } else {
            DEFAULT_TILES
        }
    }
    
    fun setTiles(tiles: List<String>) {
        val tilesJson = tiles.joinToString("|")
        prefs.edit().putString(QUICK_TILES_KEY, tilesJson).apply()
    }
    
    fun getDefaultTiles(): List<String> = DEFAULT_TILES
}
