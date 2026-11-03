package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kaaass.zerotierfix.model.Socks5Config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class Socks5PreferencesTest {
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        clearSocks5Values();
    }

    @After
    public void tearDown() {
        clearSocks5Values();
    }

    @Test
    public void settingsPersistWhenLoadedAgain() {
        Socks5Preferences.save(preferences, Socks5Config.create(true, "10.147.17.25", "9050"));

        Socks5Config restored = Socks5Preferences.load(preferences);
        assertTrue(restored.isEnabled());
        assertEquals("10.147.17.25", restored.getHost());
        assertEquals(9050, restored.getPort());
    }

    private void clearSocks5Values() {
        preferences.edit()
                .remove(Constants.PREF_SOCKS5_ENABLED)
                .remove(Constants.PREF_SOCKS5_HOST)
                .remove(Constants.PREF_SOCKS5_PORT)
                .commit();
    }
}
