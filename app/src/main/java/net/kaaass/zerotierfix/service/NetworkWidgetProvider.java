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
import net.kaaass.zerotierfix.ui.PrefsActivity;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.util.Constants;

public class NetworkWidgetProvider extends AppWidgetProvider {
    private static final int REQUEST_TOGGLE = 4101;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        int[] allIds = manager.getAppWidgetIds(new ComponentName(context, NetworkWidgetProvider.class));
        if (allIds.length == 0) return;
        java.util.Arrays.sort(allIds);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int activeId = prefs.getInt(Constants.PREF_WIDGET_ACTIVE_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (java.util.Arrays.binarySearch(allIds, activeId) < 0) {
            activeId = allIds[0];
            prefs.edit().putInt(Constants.PREF_WIDGET_ACTIVE_ID, activeId).apply();
        }
        for (int id : allIds) update(context, manager, id, id == activeId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int activeId = prefs.getInt(Constants.PREF_WIDGET_ACTIVE_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        for (int deletedId : appWidgetIds) {
            if (deletedId == activeId) {
                prefs.edit().remove(Constants.PREF_WIDGET_ACTIVE_ID).apply();
                break;
            }
        }
        refresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, NetworkWidgetProvider.class));
        new NetworkWidgetProvider().onUpdate(context, manager, ids);
        CompactNetworkWidgetProvider.refresh(context);
    }

    private static void update(Context context, AppWidgetManager manager, int id, boolean active) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(Constants.PREF_WIDGET_PENDING, false)
                && System.currentTimeMillis() - prefs.getLong(Constants.PREF_WIDGET_PENDING_AT, 0L) >= 5 * 60 * 1000L) {
            prefs.edit().putBoolean(Constants.PREF_WIDGET_PENDING, false).apply();
        }
        long networkId = prefs.getLong(Constants.PREF_WIDGET_NETWORK_ID, 0L);
        boolean pending = prefs.getBoolean(Constants.PREF_WIDGET_PENDING, false);
        boolean connected = prefs.getBoolean(Constants.PREF_WIDGET_CONNECTED, false);
        boolean available = networkId != 0L && ((ZerotierFixApplication) context.getApplicationContext())
                .getDaoSession().getNetworkDao().queryBuilder()
                .where(NetworkDao.Properties.NetworkId.eq(networkId)).count() > 0;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_network_toggle);
        if (!active) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_duplicate_title));
            views.setTextViewText(R.id.widget_toggle, context.getString(R.string.widget_duplicate_message));
            views.setBoolean(R.id.widget_toggle, "setEnabled", false);
            views.setOnClickPendingIntent(R.id.widget_toggle, null);
            manager.updateAppWidget(id, views);
            return;
        }
        if (networkId == 0) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_not_configured));
            views.setTextViewText(R.id.widget_toggle, context.getString(R.string.widget_configure));
            Intent configure = new Intent(context, PrefsActivity.class);
            PendingIntent pi = PendingIntent.getActivity(context, 4102, configure, pendingFlags());
            views.setOnClickPendingIntent(R.id.widget_toggle, pi);
        } else if (!available) {
            String idText = String.format(java.util.Locale.ROOT, "%016x", networkId);
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_network_unavailable, idText));
            views.setTextViewText(R.id.widget_toggle, context.getString(R.string.widget_configure));
            Intent configure = new Intent(context, PrefsActivity.class);
            PendingIntent pi = PendingIntent.getActivity(context, 4102, configure, pendingFlags());
            views.setOnClickPendingIntent(R.id.widget_toggle, pi);
        } else {
            String idText = String.format(java.util.Locale.ROOT, "%016x", networkId);
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_network_id, idText));
            views.setTextViewText(R.id.widget_toggle, context.getString(pending
                    ? R.string.widget_pending : (connected ? R.string.widget_disconnect : R.string.widget_connect)));
            Intent toggle = new Intent(context, NetworkWidgetActionReceiver.class)
                    .setAction(NetworkWidgetActionReceiver.ACTION_TOGGLE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_TOGGLE, toggle, pendingFlags());
            views.setOnClickPendingIntent(R.id.widget_toggle, pi);
        }
        manager.updateAppWidget(id, views);
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}
