package de.flaechenlese.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Speichert den Stand, den die Web-App an die Android-App übergibt (für Widget und Benachrichtigungen). */
object Store {
    private const val PREFS = "flaechenlese"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): String = LocalDate.now().toString()

    fun saveSnapshot(c: Context, json: String) {
        prefs(c).edit().putString("snapshot", json).apply()
    }

    fun snapshot(c: Context): JSONObject? {
        val raw = prefs(c).getString("snapshot", null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    data class Day(val passage: String?, val read: Boolean, val url: String?)

    private fun dayJson(s: JSONObject?, plan: String, date: String): JSONObject? =
        s?.optJSONObject("plans")?.optJSONObject(plan)?.optJSONObject("days")?.optJSONObject(date)

    fun day(c: Context, plan: String, date: String = today()): Day {
        val d = dayJson(snapshot(c), plan, date) ?: return Day(null, reads(c, plan).contains(date), null)
        val p = d.optString("p", "")
        val u = d.optString("u", "")
        return Day(if (p.isBlank()) null else p, d.optBoolean("r", false), if (u.isBlank()) null else u)
    }

    fun activePlan(c: Context): String = snapshot(c)?.optString("active", "LARGE") ?: "LARGE"

    /** Alle als gelesen markierten Tage (yyyy-MM-dd) eines Plans. */
    fun reads(c: Context, plan: String): Set<String> {
        val a = snapshot(c)?.optJSONObject("plans")?.optJSONObject(plan)?.optJSONArray("reads") ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    fun hasPlan(c: Context, plan: String): Boolean =
        snapshot(c)?.optJSONObject("plans")?.has(plan) == true

    fun planName(c: Context, plan: String): String {
        val n = snapshot(c)?.optJSONObject("plans")?.optJSONObject(plan)?.optString("name", "") ?: ""
        if (n.isNotBlank()) return n
        return when (plan) {
            "LARGE" -> "Große Flächenlese"
            "SMALL" -> "Kleine Flächenlese"
            else -> "Leseplan"
        }
    }

    /** Wird vom Widget aufgerufen: Status sofort umschalten und für die Web-App vormerken. */
    fun toggleFromWidget(c: Context, plan: String, date: String): Boolean = synchronized(lock) {
        val s = snapshot(c) ?: return false
        val d = dayJson(s, plan, date) ?: return false
        val newRead = !d.optBoolean("r", false)
        d.put("r", newRead)
        val planObj = s.optJSONObject("plans")?.optJSONObject(plan)
        if (planObj != null) {
            val old = planObj.optJSONArray("reads") ?: JSONArray()
            val set = (0 until old.length()).map { old.getString(it) }.toMutableSet()
            if (newRead) set.add(date) else set.remove(date)
            planObj.put("reads", JSONArray(set.toList()))
        }
        saveSnapshot(c, s.toString())
        val arr = try { JSONArray(prefs(c).getString("pending", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("plan", plan).put("date", date).put("read", newRead))
        prefs(c).edit().putString("pending", arr.toString()).apply()
        true
    }

    fun takePending(c: Context): String = synchronized(lock) {
        val p = prefs(c).getString("pending", "[]") ?: "[]"
        prefs(c).edit().putString("pending", "[]").apply()
        p
    }
}
