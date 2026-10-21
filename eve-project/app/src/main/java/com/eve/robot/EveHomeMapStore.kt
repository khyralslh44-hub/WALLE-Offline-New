package com.eve.robot

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-only persistent home map storage. The JSON lives in the app's private SharedPreferences;
 * no map, room hypothesis, or travelled route is uploaded.
 */
class EveHomeMapStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): EveHomeMapPersistedState? {
        val encoded = preferences.getString(KEY_MAP, null) ?: return null
        if (encoded.length > MAX_STATE_JSON_CHARS) {
            Log.w(TAG, "Stored home map exceeds size limit; refusing to load it.")
            return null
        }
        return try {
            val root = JSONObject(encoded)
            val rawCells = root.getJSONArray("cells")
            val rawRoute = root.getJSONArray("routePoints")
            if (rawCells.length() > MAX_STORED_CELLS || rawRoute.length() > EveHomeMapMemory.MAX_ROUTE_POINTS) {
                Log.w(TAG, "Stored home map exceeds collection limits; refusing to load it.")
                return null
            }
            val cells = ArrayList<EveHomeMapStoredCell>(rawCells.length())
            for (i in 0 until rawCells.length()) {
                val item = rawCells.getJSONObject(i)
                val votesObject = item.optJSONObject("roomVotes") ?: JSONObject()
                val votes = LinkedHashMap<EveRoomType, Int>()
                val iterator = votesObject.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val room = runCatching { EveRoomType.valueOf(key) }.getOrNull() ?: return null
                    votes[room] = votesObject.getInt(key)
                }
                cells += EveHomeMapStoredCell(
                    x = item.getInt("x"),
                    y = item.getInt("y"),
                    evidence = item.getInt("evidence"),
                    roomVotes = votes
                )
            }
            val route = ArrayList<EveHomeRoutePoint>(rawRoute.length())
            for (i in 0 until rawRoute.length()) {
                val item = rawRoute.getJSONObject(i)
                route += EveHomeRoutePoint(
                    xMm = item.getInt("xMm"),
                    yMm = item.getInt("yMm"),
                    headingDegrees = item.getDouble("headingDegrees").toFloat()
                )
            }
            EveHomeMapPersistedState(
                version = root.getInt("version"),
                cellSizeMm = root.getInt("cellSizeMm"),
                capacity = root.getInt("capacity"),
                cells = cells,
                routePoints = route,
                savedAtEpochMs = root.getLong("savedAtEpochMs")
            )
        } catch (error: Exception) {
            Log.w(TAG, "Stored home map is unreadable; it will not be used.", error)
            null
        }
    }

    fun save(state: EveHomeMapPersistedState): Boolean {
        if (state.cells.size > MAX_STORED_CELLS ||
            state.routePoints.size > EveHomeMapMemory.MAX_ROUTE_POINTS
        ) return false
        return try {
            val root = JSONObject()
                .put("version", state.version)
                .put("cellSizeMm", state.cellSizeMm)
                .put("capacity", state.capacity)
                .put("savedAtEpochMs", state.savedAtEpochMs)
            val cells = JSONArray()
            state.cells.forEach { cell ->
                val votes = JSONObject()
                cell.roomVotes.forEach { (room, count) -> votes.put(room.name, count) }
                cells.put(
                    JSONObject()
                        .put("x", cell.x)
                        .put("y", cell.y)
                        .put("evidence", cell.evidence)
                        .put("roomVotes", votes)
                )
            }
            val route = JSONArray()
            state.routePoints.forEach { point ->
                route.put(
                    JSONObject()
                        .put("xMm", point.xMm)
                        .put("yMm", point.yMm)
                        .put("headingDegrees", point.headingDegrees.toDouble())
                )
            }
            root.put("cells", cells).put("routePoints", route)
            val encoded = root.toString()
            if (encoded.length > MAX_STATE_JSON_CHARS) return false
            preferences.edit().putString(KEY_MAP, encoded).apply()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not persist home map locally.", error)
            false
        }
    }

    fun clear() {
        preferences.edit().remove(KEY_MAP).apply()
    }

    companion object {
        private const val TAG = "EVE_MAP_STORE"
        private const val PREFERENCES_NAME = "eve_private_home_map"
        private const val KEY_MAP = "home_map_json_v1"
        private const val MAX_STORED_CELLS = 20_000
        private const val MAX_STATE_JSON_CHARS = 1_000_000
    }
}
