package com.upravind.lumen

import android.content.Context

/** Small saved settings so your choices survive closing the app. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    var flareOn: Boolean get() = sp.getBoolean("flareOn", true); set(v) { sp.edit().putBoolean("flareOn", v).apply() }
    var flareStrength: Float get() = sp.getFloat("flareStrength", 1f); set(v) { sp.edit().putFloat("flareStrength", v).apply() }
    var grid: Int get() = sp.getInt("grid", 1); set(v) { sp.edit().putInt("grid", v).apply() }
    var look: String get() = sp.getString("look", "ORIGINAL") ?: "ORIGINAL"; set(v) { sp.edit().putString("look", v).apply() }
    var tourSeen: Boolean get() = sp.getBoolean("tourSeen", false); set(v) { sp.edit().putBoolean("tourSeen", v).apply() }
    // My Look sliders
    var myWarm: Float get() = sp.getFloat("myWarm", 6500f); set(v) { sp.edit().putFloat("myWarm", v).apply() }
    var myContrast: Float get() = sp.getFloat("myContrast", 1f); set(v) { sp.edit().putFloat("myContrast", v).apply() }
    var mySat: Float get() = sp.getFloat("mySat", 1f); set(v) { sp.edit().putFloat("mySat", v).apply() }
    var myVignette: Float get() = sp.getFloat("myVignette", 0f); set(v) { sp.edit().putFloat("myVignette", v).apply() }
    var myGrain: Float get() = sp.getFloat("myGrain", 0f); set(v) { sp.edit().putFloat("myGrain", v).apply() }
}
