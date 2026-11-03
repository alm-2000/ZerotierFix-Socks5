package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.net.VpnService;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ui.NetworkListActivity;
import net.kaaass.zerotierfix.ui.PrefsActivity;
import net.kaaass.zerotierfix.util.Constants;

public final class NetworkWidgetActionReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE = "net.kaaass.zerotierfix.widget.TOGGLE";
    public static final String EXTRA_COMPACT_WIDGET = "net.kaaass.zerotierfix.widget.COMPACT";
    public NetworkWidgetActionReceiver() { }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            int widgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID);
            String activeIdPreference = intent.getBooleanExtra(EXTRA_COMPACT_WIDGET, false)
                    ? Constants.PREF_WIDGET_COMPACT_ACTIVE_ID : Constants.PREF_WIDGET_ACTIVE_ID;
            int activeId = PreferenceManager.getDefaultSharedPreferences(context)
                    .getInt(activeIdPreference, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetId == activeId) {
                handleToggle(context, intent.getBooleanExtra(EXTRA_COMPACT_WIDGET, false));
            }
        }
    }

    public static void handleToggle(Context context) {
        handleToggle(context, false);
    }

    private static void handleToggle(Context context, boolean compactWidget) {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long networkId = prefs.getLong(Constants.PREF_WIDGET_NETWORK_ID, 0L);
        if (networkId == 0L) {
            Intent configure = new Intent(context, PrefsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(configure);
            return;
        }
        long pendingAt = prefs.getLong(Constants.PREF_WIDGET_PENDING_AT, 0L);
        boolean connected = prefs.getBoolean(Constants.PREF_WIDGET_CONNECTED, false);
        if (prefs.getBoolean(Constants.PREF_WIDGET_PENDING, false)
                && System.currentTimeMillis() - pendingAt < 5 * 60 * 1000L) {
            if (!connected) {
                // Tapping either widget while connecting cancels the in-flight join.
                Intent stop = new Intent(context, ZeroTierOneService.class)
                        .setAction(ZeroTierOneService.ACTION_WIDGET_DISCONNECT)
                        .putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId);
                try {
                    context.startService(stop);
                } catch (RuntimeException e) {
                    Toast.makeText(context, R.string.widget_action_failed, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (!connected && VpnService.prepare(context) != null) {
            // VPN consent is an activity-only flow in the existing app. Preserve it; never bypass it.
            context.startActivity(new Intent(context, NetworkListActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(context, R.string.widget_vpn_authorization_required, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putBoolean(Constants.PREF_WIDGET_PENDING, true)
                .putLong(Constants.PREF_WIDGET_PENDING_AT, System.currentTimeMillis()).apply();
        Intent service = new Intent(context, ZeroTierOneService.class)
                .setAction(connected ? ZeroTierOneService.ACTION_WIDGET_DISCONNECT : ZeroTierOneService.ACTION_WIDGET_CONNECT)
                .putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId);
        try {
            if (connected) context.startService(service);
            else ContextCompat.startForegroundService(context, service);
            NetworkWidgetProvider.refresh(context);
        } catch (RuntimeException e) {
            prefs.edit().putBoolean(Constants.PREF_WIDGET_PENDING, false).apply();
            NetworkWidgetProvider.refresh(context);
            Toast.makeText(context, R.string.widget_action_failed, Toast.LENGTH_LONG).show();
        }
    }
}
