package com.github.catvod.utils;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.github.catvod.Init;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Prefers {

    /*
     * Ephemeral compatibility values for spiders which still read credentials
     * through Prefers.getString().  These values deliberately never enter the
     * SharedPreferences XML file and disappear with the process.
     */
    private static final Map<String, String> RUNTIME_STRINGS = new ConcurrentHashMap<>();

    public static SharedPreferences getPrefers() {
        return PreferenceManager.getDefaultSharedPreferences(Init.context());
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static String getString(String key, String defaultValue) {
        try {
            if (RUNTIME_STRINGS.containsKey(key)) return RUNTIME_STRINGS.get(key);
            return getPrefers().getString(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void putRuntimeString(String key, String value) {
        if (key == null || key.isEmpty() || value == null || value.isEmpty()) return;
        RUNTIME_STRINGS.put(key, value);
    }

    public static void removeRuntimeString(String key) {
        if (key != null) RUNTIME_STRINGS.remove(key);
    }

    public static void clearRuntimeStrings() {
        RUNTIME_STRINGS.clear();
    }

    public static int getInt(String key) {
        return getInt(key, 0);
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return getPrefers().getInt(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static long getLong(String key) {
        return getLong(key, 0L);
    }

    public static long getLong(String key, long defaultValue) {
        try {
            return getPrefers().getLong(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public static float getFloat(String key, float defaultValue) {
        try {
            return getPrefers().getFloat(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        try {
            return getPrefers().getBoolean(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void put(String key, Object obj) {
        if (obj == null) return;
        if (obj instanceof String val) {
            if (RUNTIME_STRINGS.containsKey(key)) {
                putRuntimeString(key, val);
                return;
            }
            getPrefers().edit().putString(key, val).apply();
        } else if (obj instanceof Boolean val) {
            getPrefers().edit().putBoolean(key, val).apply();
        } else if (obj instanceof Float val) {
            getPrefers().edit().putFloat(key, val).apply();
        } else if (obj instanceof Integer val) {
            getPrefers().edit().putInt(key, val).apply();
        } else if (obj instanceof Long val) {
            getPrefers().edit().putLong(key, val).apply();
        } else if (obj instanceof Number val) {
            if (val.toString().contains(".")) put(key, val.floatValue());
            else put(key, val.intValue());
        }
    }

    public static void remove(String key) {
        removeRuntimeString(key);
        getPrefers().edit().remove(key).apply();
    }
}
