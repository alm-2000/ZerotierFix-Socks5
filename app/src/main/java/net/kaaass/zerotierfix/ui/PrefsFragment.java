package net.kaaass.zerotierfix.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.service.ZeroTierOneService;
import net.kaaass.zerotierfix.events.Socks5SettingsChangedEvent;
import net.kaaass.zerotierfix.model.Socks5Config;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.service.NetworkWidgetProvider;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.FileUtil;
import net.kaaass.zerotierfix.util.Socks5AppPreferences;

import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

/**
 * 设置页面 fragment
 */
public class PrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final int PLANET_DOWNLOAD_CONN_TIMEOUT = 5 * 1000;
    public static final int PLANET_DOWNLOAD_TIMEOUT = 10 * 1000;
    private static final String TAG = "PreferencesFragment";
    /**
     * Plant 文件固定头
     */
    private static final byte[] PLANET_FILE_HEADER = new byte[]{
            0x01,  // World type, 0x01 = planet
    };
    private SwitchPreference prefPlanetUseCustom;
    private Preference prefSetPlanetFile;
    private SwitchPreference prefSocks5Enabled;
    private Socks5ValueEditTextPreference prefSocks5Host;
    private Socks5ValueEditTextPreference prefSocks5Port;
    private Preference prefSocks5Apps;
    private Preference prefWidgetNetwork;
    private Dialog planetDialog = null;
    private Dialog loadingDialog = null;
    private ActivityResultLauncher<Intent> planetFileSelectLauncher = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 初始化 Planet 文件选择结果回调
        this.planetFileSelectLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            var result = activityResult.getResultCode();
            var data = activityResult.getData();
            if (result == -1 && data != null) {
                // Planet 自定义文件设置
                Uri uriData = data.getData();
                if (uriData == null) {
                    Log.e(TAG, "Invalid planet URI");
                    return;
                }
                // 复制文件
                try (InputStream in = requireContext().getContentResolver().openInputStream(uriData)) {
                    FileUtils.copyInputStreamToFile(in, FileUtil.tempFile(requireContext()));
                    boolean success = dealTempPlanetFile();
                    if (!success) {
                        // 校验失败
                        Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                        closePlanetDialog();
                        return;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Cannot copy planet file", e);
                    // 设置失败
                    Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
                    closePlanetDialog();
                    return;
                } finally {
                    FileUtil.clearTempFile(requireContext());
                }
                Log.i(TAG, "Copy planet file successfully");
                // 关闭对话框
                Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                closePlanetDialog();
            } else {
                Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);

        this.prefPlanetUseCustom = findPreference(Constants.PREF_PLANET_USE_CUSTOM);
        this.prefSetPlanetFile = findPreference(Constants.PREF_SET_PLANET_FILE);
        this.prefSocks5Enabled = findPreference(Constants.PREF_SOCKS5_ENABLED);
        this.prefSocks5Host = findPreference(Constants.PREF_SOCKS5_HOST);
        this.prefSocks5Port = findPreference(Constants.PREF_SOCKS5_PORT);
        this.prefSocks5Apps = findPreference(Constants.PREF_SOCKS5_APPLICATIONS);
        this.prefWidgetNetwork = findPreference(Constants.PREF_WIDGET_NETWORK_ID);
        Objects.requireNonNull(this.prefWidgetNetwork).setOnPreferenceClickListener(preference -> {
            showWidgetNetworkDialog();
            return true;
        });
        updateWidgetNetworkSummary();
        Objects.requireNonNull(this.prefSocks5Host).setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        Objects.requireNonNull(this.prefSocks5Port).setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        Objects.requireNonNull(this.prefSocks5Apps).setOnPreferenceClickListener(preference -> {
            showSocks5AppsDialog();
            return true;
        });
        updateSocks5AppsSummary();

        Objects.requireNonNull(this.prefSocks5Enabled).setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (Boolean) newValue;
            try {
                Socks5Config.create(enabled,
                        getPreferenceManager().getSharedPreferences().getString(Constants.PREF_SOCKS5_HOST, ""),
                        getPreferenceManager().getSharedPreferences().getString(Constants.PREF_SOCKS5_PORT,
                                Integer.toString(Socks5Config.DEFAULT_PORT)));
                return true;
            } catch (IllegalArgumentException e) {
                Toast.makeText(getContext(),
                        enabled && isHostMissing() ? R.string.preferences_socks5_host_required
                                : R.string.preferences_socks5_port_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
        });
        Objects.requireNonNull(this.prefSocks5Host).setOnPreferenceChangeListener((preference, newValue) -> {
            try {
                Socks5Config.create(isSocks5Enabled(), (String) newValue,
                        getPreferenceManager().getSharedPreferences().getString(Constants.PREF_SOCKS5_PORT,
                                Integer.toString(Socks5Config.DEFAULT_PORT)));
                return true;
            } catch (IllegalArgumentException e) {
                Toast.makeText(getContext(), R.string.preferences_socks5_host_required, Toast.LENGTH_LONG).show();
                return false;
            }
        });
        Objects.requireNonNull(this.prefSocks5Port).setOnPreferenceChangeListener((preference, newValue) -> {
            try {
                Socks5Config.create(isSocks5Enabled(),
                        getPreferenceManager().getSharedPreferences().getString(Constants.PREF_SOCKS5_HOST, ""),
                        (String) newValue);
                return true;
            } catch (IllegalArgumentException e) {
                Toast.makeText(getContext(), R.string.preferences_socks5_port_invalid, Toast.LENGTH_LONG).show();
                return false;
            }
        });

        // Plane 配置开关
        Objects.requireNonNull(this.prefPlanetUseCustom).setOnPreferenceClickListener(preference -> {
            // 判断状态
            updatePlanetSetting();
            var pref = preference.getSharedPreferences();
            if (pref == null || !pref.getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false)) {
                // 设置为假时直接退出
                return true;
            }
            // 设置为真时，如果还没有设置文件则提示设置文件
            if (customPlanetFileNotExit()) {
                showPlanetDialog();
            }
            return true;
        });

        // 打开 Plant 文件设置
        Objects.requireNonNull(this.prefSetPlanetFile).setOnPreferenceClickListener(preference -> {
            showPlanetDialog();
            return true;
        });
        updatePlanetSetting();
    }

    private void showWidgetNetworkDialog() {
        java.util.List<Network> networks = ((ZerotierFixApplication) requireActivity().getApplication())
                .getDaoSession().getNetworkDao().queryBuilder()
                .orderAsc(NetworkDao.Properties.NetworkId).list();
        if (networks.isEmpty()) {
            new AlertDialog.Builder(requireContext()).setTitle(R.string.widget_network_setting_title)
                    .setMessage(R.string.widget_no_networks).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] labels = new String[networks.size()];
        for (int i = 0; i < networks.size(); i++) {
            Network network = networks.get(i);
            String name = network.getNetworkName();
            if (name == null || name.trim().isEmpty()) name = getString(R.string.empty_network_name);
            labels[i] = name + " (" + network.getNetworkIdStr() + ")";
        }
        new AlertDialog.Builder(requireContext()).setTitle(R.string.widget_network_setting_title)
                .setItems(labels, (dialog, which) -> {
                    Network selected = networks.get(which);
                    getPreferenceManager().getSharedPreferences().edit()
                            .putLong(Constants.PREF_WIDGET_NETWORK_ID, selected.getNetworkId())
                            .putBoolean(Constants.PREF_WIDGET_CONNECTED, false)
                            .putBoolean(Constants.PREF_WIDGET_PENDING, false).apply();
                    updateWidgetNetworkSummary();
                    NetworkWidgetProvider.refresh(requireContext());
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateWidgetNetworkSummary() {
        if (prefWidgetNetwork == null || getPreferenceManager().getSharedPreferences() == null) return;
        long id = getPreferenceManager().getSharedPreferences().getLong(Constants.PREF_WIDGET_NETWORK_ID, 0L);
        if (id == 0L) {
            prefWidgetNetwork.setSummary(R.string.widget_network_setting_summary);
            return;
        }
        Network selected = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession()
                .getNetworkDao().queryBuilder().where(NetworkDao.Properties.NetworkId.eq(id)).unique();
        prefWidgetNetwork.setSummary(selected == null
                ? getString(R.string.widget_network_unavailable, String.format(java.util.Locale.ROOT, "%016x", id))
                : getString(R.string.widget_network_selected, selected.getNetworkIdStr()));
    }

    private boolean isSocks5Enabled() {
        return getPreferenceManager().getSharedPreferences()
                .getBoolean(Constants.PREF_SOCKS5_ENABLED, false);
    }

    private boolean isHostMissing() {
        String host = getPreferenceManager().getSharedPreferences()
                .getString(Constants.PREF_SOCKS5_HOST, "");
        return host == null || host.trim().isEmpty();
    }

    private void updateSocks5AppsSummary() {
        if (prefSocks5Apps == null || getContext() == null) return;
        int count = Socks5AppPreferences.load(requireContext()).size();
        if (count == 0) prefSocks5Apps.setSummary(R.string.preferences_socks5_apps_empty);
        else prefSocks5Apps.setSummary(getString(R.string.preferences_socks5_apps_count, count));

    }

    private void refreshSocks5DisplayedValues() {
        if (prefSocks5Host != null) prefSocks5Host.refreshDisplayedValue();
        if (prefSocks5Port != null) prefSocks5Port.refreshDisplayedValue();
    }

    private void showSocks5AppsDialog() {
        PackageManager packageManager = requireContext().getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<ApplicationInfo> applications = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo resolved : packageManager.queryIntentActivities(launcherIntent, 0)) {
            ApplicationInfo info = resolved.activityInfo.applicationInfo;
            if (info == null || getContext().getPackageName().equals(info.packageName)
                    || !seenPackages.add(info.packageName)) continue;
            applications.add(info);
        }
        Collections.sort(applications, Comparator.comparing(
                info -> info.loadLabel(packageManager).toString(), String.CASE_INSENSITIVE_ORDER));

        Set<String> checked = new HashSet<>(Socks5AppPreferences.load(requireContext()));
        ListView list = new ListView(requireContext());
        ArrayList<ApplicationInfo> visibleApplications = new ArrayList<>(applications);
        Socks5ApplicationAdapter adapter = new Socks5ApplicationAdapter(packageManager,
                applications, visibleApplications, checked);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ApplicationInfo info = visibleApplications.get(position);
            if (!checked.add(info.packageName)) checked.remove(info.packageName);
            adapter.notifyDataSetChanged();
        });

        EditText search = new EditText(requireContext());
        search.setSingleLine(true);
        search.setHint(R.string.preferences_socks5_apps_search_hint);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                String normalizedQuery = query.toLowerCase(java.util.Locale.ROOT);
                visibleApplications.clear();
                for (ApplicationInfo app : applications) {
                    String name = app.loadLabel(packageManager).toString();
                    if (normalizedQuery.isEmpty() || name.toLowerCase(java.util.Locale.ROOT)
                            .contains(normalizedQuery)) {
                        visibleApplications.add(app);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        android.widget.LinearLayout bulkActions = new android.widget.LinearLayout(requireContext());
        bulkActions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button selectAll = new android.widget.Button(requireContext());
        selectAll.setText(R.string.preferences_socks5_apps_select_all);
        selectAll.setOnClickListener(v -> {
            for (ApplicationInfo app : applications) checked.add(app.packageName);
            adapter.notifyDataSetChanged();
        });
        android.widget.Button deselectAll = new android.widget.Button(requireContext());
        deselectAll.setText(R.string.preferences_socks5_apps_deselect_all);
        deselectAll.setOnClickListener(v -> {
            for (ApplicationInfo app : applications) checked.remove(app.packageName);
            adapter.notifyDataSetChanged();
        });
        bulkActions.addView(selectAll, new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bulkActions.addView(deselectAll, new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView note = new TextView(requireContext());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        note.setPadding(pad, pad, pad, pad / 2);
        note.setText(R.string.preferences_socks5_service_note);

        android.widget.LinearLayout content = new android.widget.LinearLayout(requireContext());
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(note);
        content.addView(search, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(bulkActions, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(list, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (400 * getResources().getDisplayMetrics().density)));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.preferences_socks5_apps_dialog)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Socks5AppPreferences.save(getPreferenceManager().getSharedPreferences(), checked);
                    updateSocks5AppsSummary();
                    EventBus.getDefault().post(new Socks5SettingsChangedEvent());
                })
                .show();
    }

    private static final class Socks5ApplicationAdapter extends BaseAdapter {
        private final PackageManager packageManager;
        private final ArrayList<ApplicationInfo> visibleApplications;
        private final Set<String> checked;

        Socks5ApplicationAdapter(PackageManager packageManager, ArrayList<ApplicationInfo> applications,
                                 ArrayList<ApplicationInfo> visibleApplications, Set<String> checked) {
            this.packageManager = packageManager;
            this.visibleApplications = visibleApplications;
            this.checked = checked;
        }

        @Override public int getCount() { return visibleApplications.size(); }
        @Override public ApplicationInfo getItem(int position) { return visibleApplications.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_socks5_application,
                        parent, false);
            }
            ApplicationInfo app = getItem(position);
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(app.loadIcon(packageManager));
            ((TextView) row.findViewById(R.id.app_name)).setText(app.loadLabel(packageManager));
            ((TextView) row.findViewById(R.id.app_package)).setText(app.packageName);
            ((CheckBox) row.findViewById(R.id.app_selected)).setChecked(checked.contains(app.packageName));
            return row;
        }
    }

    /**
     * 显示选择 Planet 文件来源对话框
     */
    private void showPlanetDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_custom_planet_select, null);

        // 选择文件
        View viewFile = view.findViewById(R.id.from_file);
        viewFile.setOnClickListener(v -> this.showPlanetFromFileDialog());

        // 选择 URL
        View viewUrl = view.findViewById(R.id.from_url);
        viewUrl.setOnClickListener(v -> this.showPlanetFromUrlDialog());

        // 窗口
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.load_planet_file)
                .setOnCancelListener(dialog -> closePlanetDialog());

        this.planetDialog = builder.create();
        this.planetDialog.show();
    }

    /**
     * 显示选择 Planet 文件的文件选择器
     */
    private void showPlanetFromFileDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        this.planetFileSelectLauncher.launch(intent);
    }

    /**
     * 显示输入 Planet 文件 URL 对话框
     */
    private void showPlanetFromUrlDialog() {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_planet_url, null);

        final EditText editText = view.findViewById(R.id.planet_url);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.import_via_url)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = editText.getText().toString();
                    URL fileUrl;
                    try {
                        fileUrl = new URL(url);
                    } catch (MalformedURLException e) {
                        Toast.makeText(getContext(), R.string.wrong_url_format, Toast.LENGTH_LONG).show();
                        // 不必关闭对话框
                        return;
                    }

                    // 关闭对话框
                    if (this.planetDialog != null) {
                        this.planetDialog.dismiss();
                        this.planetDialog = null;
                    }
                    // 显示加载动画
                    showLoadingDialog(R.string.downloading);
                    // 下载 Planet 文件
                    new Thread(() -> {
                        try {
                            FileUtils.copyURLToFile(fileUrl,
                                    FileUtil.tempFile(requireContext()),
                                    PLANET_DOWNLOAD_CONN_TIMEOUT, PLANET_DOWNLOAD_TIMEOUT);
                            boolean success = dealTempPlanetFile();
                            if (!success) {
                                // 校验失败
                                requireActivity().runOnUiThread(() -> {
                                    Toast.makeText(getContext(), R.string.planet_wrong_format, Toast.LENGTH_LONG).show();
                                    closePlanetDialog();
                                    closeLoadingDialog();
                                });
                                return;
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Cannot download planet file", e);
                            // 设置失败
                            requireActivity().runOnUiThread(() -> {
                                int message = R.string.cannot_download_planet_file;
                                if (e instanceof SocketTimeoutException) {
                                    message = R.string.planet_download_timeout;
                                }
                                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                                closePlanetDialog();
                                closeLoadingDialog();
                            });
                            return;
                        } finally {
                            FileUtil.clearTempFile(requireContext());
                        }

                        // 关闭对话框
                        requireActivity().runOnUiThread(() -> {
                            Snackbar.make(requireView(), R.string.set_planet_succ, BaseTransientBottomBar.LENGTH_LONG).show();
                            closePlanetDialog();
                            closeLoadingDialog();
                        });
                    }).start();
                });

        builder.create().show();
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
        refreshSocks5DisplayedValues();
    }

    @Override
    public void onPause() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Constants.PREF_SOCKS5_ENABLED.equals(key)
                || Constants.PREF_SOCKS5_HOST.equals(key)
                || Constants.PREF_SOCKS5_PORT.equals(key)) {
            EventBus.getDefault().post(new Socks5SettingsChangedEvent());
            refreshSocks5DisplayedValues();
        } else if (key.equals(Constants.PREF_NETWORK_USE_CELLULAR_DATA)) {
            // 移动网络数据配置
            if (sharedPreferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false)) {
                requireActivity().startService(new Intent(getActivity(), ZeroTierOneService.class));
            }
        }
    }

    /**
     * 检查是否存在 Planet 文件
     */
    private boolean customPlanetFileNotExit() {
        File file = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return !file.exists();
    }

    /**
     * 关闭 Planet 对话框
     */
    private void closePlanetDialog() {
        // 关闭对话框
        if (this.planetDialog != null) {
            this.planetDialog.dismiss();
            this.planetDialog = null;
        }
        // 如果没有设置文件，就取消选项
        if (customPlanetFileNotExit()) {
            this.prefPlanetUseCustom.setChecked(false);
        }
    }

    /**
     * 更新 Planet 设置选项
     */
    private void updatePlanetSetting() {
        boolean useCustom = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
        this.prefSetPlanetFile.setEnabled(useCustom);
    }

    /**
     * 将临时文件设置为 Planet 文件
     */
    private boolean dealTempPlanetFile() {
        // Plant 文件校验
        File temp = FileUtil.tempFile(requireContext());
        byte[] buf = new byte[PLANET_FILE_HEADER.length];
        try (FileInputStream in = new FileInputStream(temp)) {
            // 读入文件头
            if (in.read(buf) != PLANET_FILE_HEADER.length) {
                return false;
            }
            // 校验
            if (!Arrays.equals(buf, PLANET_FILE_HEADER)) {
                Log.i(TAG, "Planet file has a wrong header");
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        // 移动临时文件
        File dest = new File(requireActivity().getFilesDir(), Constants.FILE_CUSTOM_PLANET);
        return temp.renameTo(dest);
    }

    /**
     * 显示加载框
     */
    private void showLoadingDialog(int prompt) {
        closeLoadingDialog();
        // 创建
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);

        TextView textPrompt = view.findViewById(R.id.prompt);
        textPrompt.setText(prompt);

        this.loadingDialog = new AlertDialog.Builder(getContext())
                .setView(view)
                .setCancelable(false)
                .create();
        this.loadingDialog.show();
    }

    /**
     * 关闭加载框
     */
    private void closeLoadingDialog() {
        if (this.loadingDialog != null) {
            this.loadingDialog.dismiss();
        }
    }
}
