package net.kaaass.zerotierfix.service;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.ui.PrefsActivity;
import net.kaaass.zerotierfix.util.Constants;

/** Icon-only 1x1 widget for the selected ZeroTier network. */
public class CompactNetworkWidgetProvider extends AppWidgetProvider {
    private static final int REQUEST_TOGGLE = 4103;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        int[] allIds = manager.getAppWidgetIds(new ComponentName(context, CompactNetworkWidgetProvider.class));
        if (allIds.length == 0) return;
        java.util.Arrays.sort(allIds);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int activeId = prefs.getInt(Constants.PREF_WIDGET_COMPACT_ACTIVE_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (java.util.Arrays.binarySearch(allIds, activeId) < 0) {
            activeId = allIds[0];
            prefs.edit().putInt(Constants.PREF_WIDGET_COMPACT_ACTIVE_ID, activeId).apply();
        }
        for (int id : allIds) update(context, manager, id, id == activeId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int activeId = prefs.getInt(Constants.PREF_WIDGET_COMPACT_ACTIVE_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        for (int deletedId : appWidgetIds) {
            if (deletedId == activeId) {
                prefs.edit().remove(Constants.PREF_WIDGET_COMPACT_ACTIVE_ID).apply();
                break;
            }
        }
        refresh(context);
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CompactNetworkWidgetProvider.class));
        new CompactNetworkWidgetProvider().onUpdate(context, manager, ids);
    }

    private static void update(Context context, AppWidgetManager manager, int id, boolean active) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(Constants.PREF_WIDGET_PENDING, false)
                && System.currentTimeMillis() - prefs.getLong(Constants.PREF_WIDGET_PENDING_AT, 0L)
                >= 5 * 60 * 1000L) {
            prefs.edit().putBoolean(Constants.PREF_WIDGET_PENDING, false).apply();
        }

        long networkId = prefs.getLong(Constants.PREF_WIDGET_NETWORK_ID, 0L);
        boolean connected = prefs.getBoolean(Constants.PREF_WIDGET_CONNECTED, false);
        boolean connecting = prefs.getBoolean(Constants.PREF_WIDGET_PENDING, false) && !connected;
        boolean available = networkId != 0L && ((ZerotierFixApplication) context.getApplicationContext())
                .getDaoSession().getNetworkDao().queryBuilder()
                .where(NetworkDao.Properties.NetworkId.eq(networkId)).count() > 0;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_network_icon);
        views.setImageViewResource(R.id.widget_icon,
                connecting ? R.drawable.widget_sliders_middle
                        : (connected ? R.drawable.widget_sliders_on : R.drawable.widget_sliders_off));
        views.setContentDescription(R.id.widget_icon, context.getString(connecting
                ? R.string.widget_compact_connecting
                : (connected ? R.string.widget_compact_connected : R.string.widget_compact_disconnected)));

        if (active) {
            PendingIntent click;
            if (available) {
                Intent toggle = new Intent(context, NetworkWidgetActionReceiver.class)
                        .setAction(NetworkWidgetActionReceiver.ACTION_TOGGLE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(NetworkWidgetActionReceiver.EXTRA_COMPACT_WIDGET, true);
                click = PendingIntent.getBroadcast(context, REQUEST_TOGGLE, toggle, pendingFlags());
            } else {
                Intent configure = new Intent(context, PrefsActivity.class);
                click = PendingIntent.getActivity(context, REQUEST_TOGGLE, configure, pendingFlags());
            }
            views.setOnClickPendingIntent(R.id.widget_icon, click);
        }
        manager.updateAppWidget(id, views);
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}
