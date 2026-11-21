package com.antonio.inicio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Item(
    val id: String,
    val folder: Boolean,
    var key: String,
    var name: String,
    val kids: MutableList<String> = mutableListOf()
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    var items: MutableList<Item> = mutableListOf()
    var hidden: MutableSet<String> = mutableSetOf()
    var pinned: MutableList<String> = mutableListOf()
    var iconDp = 52
    var labels = true
    var wallMode = 0
    var rotation = 0

    init {
        fromJson(sp.getString("data", null))
    }

    fun save() {
        sp.edit().putString("data", toJson()).apply()
    }

    fun toJson(): String {
        val o = JSONObject()
        val arr = JSONArray()
        for (item in items) {
            val j = JSONObject()
            j.put("id", item.id)
            j.put("f", item.folder)
            j.put("k", item.key)
            j.put("n", item.name)
            j.put("c", JSONArray(item.kids))
            arr.put(j)
        }
        o.put("items", arr)
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("pinned", JSONArray(pinned))
        o.put("iconDp", iconDp)
        o.put("labels", labels)
        o.put("wallMode", wallMode)
        o.put("rotation", rotation)
        return o.toString()
    }

    fun fromJson(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        return try {
            val o = JSONObject(s)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                list.add(
                    Item(
                        j.getString("id"),
                        j.optBoolean("f", false),
                        j.optString("k", ""),
                        j.optString("n", ""),
                        strs(j.optJSONArray("c"))
                    )
                )
            }
            val h = strs(o.optJSONArray("hidden")).toMutableSet()
            val p = strs(o.optJSONArray("pinned"))
            items = list
            hidden = h
            pinned = p
            iconDp = o.optInt("iconDp", 52).coerceIn(32, 96)
            labels = o.optBoolean("labels", true)
            wallMode = o.optInt("wallMode", 0)
            rotation = o.optInt("rotation", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun strs(a: JSONArray?): MutableList<String> {
        val r = mutableListOf<String>()
        if (a != null) {
            for (i in 0 until a.length()) r.add(a.getString(i))
        }
        return r
    }
}
