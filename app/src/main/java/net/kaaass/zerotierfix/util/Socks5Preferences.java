package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import net.kaaass.zerotierfix.model.Socks5Config;

/** Persistence for SOCKS5 settings using the application's existing default preferences. */
public final class Socks5Preferences {
    private Socks5Preferences() {
    }

    public static Socks5Config load(Context context) {
        return load(PreferenceManager.getDefaultSharedPreferences(context));
    }

    public static Socks5Config load(SharedPreferences preferences) {
        Socks5Config defaults = Socks5Config.defaults();
        boolean enabled = preferences.getBoolean(Constants.PREF_SOCKS5_ENABLED, defaults.isEnabled());
        String host = preferences.getString(Constants.PREF_SOCKS5_HOST, defaults.getHost());
        String port = preferences.getString(Constants.PREF_SOCKS5_PORT,
                Integer.toString(defaults.getPort()));
        try {
            return Socks5Config.create(enabled, host, port);
        } catch (IllegalArgumentException invalidStoredSettings) {
            // Fail safely if preferences were written by an older or corrupted build.
            return Socks5Config.defaults();
        }
    }

    public static void save(SharedPreferences preferences, Socks5Config config) {
        preferences.edit()
                .putBoolean(Constants.PREF_SOCKS5_ENABLED, config.isEnabled())
                .putString(Constants.PREF_SOCKS5_HOST, config.getHost())
                .putString(Constants.PREF_SOCKS5_PORT, Integer.toString(config.getPort()))
                .apply();
    }
}
