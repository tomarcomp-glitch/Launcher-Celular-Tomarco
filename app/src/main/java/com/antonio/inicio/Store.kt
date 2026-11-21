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

class WItem(val id: Int, var h: Int)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    var widgets: MutableList<WItem> = mutableListOf()
    var items: MutableList<Item> = mutableListOf()
    var hidden: MutableSet<String> = mutableSetOf()
    var pinned: MutableList<String> = mutableListOf()
    var bar: MutableList<String> = mutableListOf()
    var iconDp = 52
    var labels = true
    var wallMode = 0
    var rotation = 0

    init {
        fromJson(sp.getString("data", null))
        loadWidgets(sp.getString("widgets", null))
    }

    fun save() {
        val wa = JSONArray()
        for (w in widgets) {
            val j = JSONObject()
            j.put("i", w.id)
            j.put("h", w.h)
            wa.put(j)
        }
        sp.edit().putString("data", toJson()).putString("widgets", wa.toString()).apply()
    }

    private fun loadWidgets(s: String?) {
        if (s.isNullOrBlank()) return
        try {
            val a = JSONArray(s)
            val list = mutableListOf<WItem>()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                list.add(WItem(j.getInt("i"), j.optInt("h", 100)))
            }
            widgets = list
        } catch (e: Exception) {
        }
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
        o.put("bar", JSONArray(bar))
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
            val b = strs(o.optJSONArray("bar"))
            items = list
            hidden = h
            pinned = p
            bar = b
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
