package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Stores package names selected for the SOCKS5 VPN path in the existing app preferences. */
public final class Socks5AppPreferences {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private Socks5AppPreferences() { }

    public static Set<String> load(Context context) {
        try {
            Set<String> stored = PreferenceManager.getDefaultSharedPreferences(context)
                    .getStringSet(Constants.PREF_SOCKS5_APPLICATIONS, Collections.emptySet());
            if (stored == null) return Collections.emptySet();
            Set<String> valid = new HashSet<>();
            for (String name : stored) {
                if (isValidPackageName(name)) valid.add(name);
            }
            return valid;
        } catch (ClassCastException malformedPreference) {
            return Collections.emptySet();
        }
    }

    public static void save(SharedPreferences preferences, Set<String> packageNames) {
        Set<String> valid = new HashSet<>();
        for (String name : packageNames) {
            if (isValidPackageName(name)) valid.add(name);
        }
        preferences.edit().putStringSet(Constants.PREF_SOCKS5_APPLICATIONS, valid).apply();
    }

    public static boolean isValidPackageName(String name) {
        return name != null && PACKAGE_NAME.matcher(name).matches();
    }
}
