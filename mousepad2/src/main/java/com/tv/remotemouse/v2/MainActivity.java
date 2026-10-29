package com.tv.remotemouse.v2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主界面：三列单屏布局。
 *
 * 状态与详情在 SharedPreferences 里存的是 ASCII 键（如 "active" / "detail_running"），
 * 显示时再映射到 string 资源，这样切换系统语言不会出现旧语言的残留。
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView adbState;
    private Button btnTakeover;
    private Button btnRelease;
    private Button backBtn;
    private LinearLayout mapList;
    private LinearLayout paramList;
    private RemoteView remote;
    private boolean liveRunning;
    private int pollLeft = 0;
    private long lastVerify = 0;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    /** 返回键三档：值写进 cfg.back，显示用字符串资源 */
    private static final String[] BACK_MODES = {"right", "side", "back"};
    private static final int[] BACK_LABELS = {
            R.string.back_right, R.string.back_side, R.string.back_native};

    /** 可映射的动作：键 → 显示名资源 */
    private static final Map<String, Integer> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put("left", R.string.act_left);
        ACTIONS.put("right", R.string.act_right);
        ACTIONS.put("wheelup", R.string.act_wheelup);
        ACTIONS.put("wheeldown", R.string.act_wheeldown);
        ACTIONS.put("home", R.string.act_home);
        ACTIONS.put("toggle", R.string.act_toggle);
    }

    private final Map<String, TextView> rowVals = new LinkedHashMap<>();
    private final Map<String, TextView> paramVals = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        adbState = findViewById(R.id.adb);
        btnTakeover = findViewById(R.id.btnTakeover);
        btnRelease = findViewById(R.id.btnRelease);
        backBtn = findViewById(R.id.btnBack);
        mapList = findViewById(R.id.mapList);
        paramList = findViewById(R.id.paramList);
        remote = findViewById(R.id.remote);

        findViewById(R.id.btnTakeover).setOnClickListener(v -> {
            start(MouseService.ACTION_TAKEOVER);
            Toast.makeText(this, R.string.toast_starting, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnRelease).setOnClickListener(v -> {
            start(MouseService.ACTION_RELEASE);
            Toast.makeText(this, R.string.toast_released, Toast.LENGTH_SHORT).show();
        });
        backBtn.setOnClickListener(v -> cycleBack());
        findViewById(R.id.btnRestore).setOnClickListener(v -> restorePreset());
        findViewById(R.id.btnAbout).setOnClickListener(v -> showAbout());
        findViewById(R.id.btnLang).setOnClickListener(v -> cycleLang());

        final EditText addr = findViewById(R.id.addr);
        addr.setText(getSharedPreferences("cfg", MODE_PRIVATE).getString("addr", "127.0.0.1:5555"));
        addr.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String a = addr.getText().toString().trim();
                if (a.isEmpty()) a = "127.0.0.1:5555";
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("addr", a).apply();
                // 退出编辑后重新移出焦点链：方向键不会再停在这里，也就不会弹输入法
                addr.setFocusable(false);
                addr.setFocusableInTouchMode(false);
                addr.setCursorVisible(false);
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(addr.getWindowToken(), 0);
                if (liveRunning && "active".equals(
                        getSharedPreferences(MouseService.PREF, MODE_PRIVATE).getString("state", "idle"))) {
                    start(MouseService.ACTION_RESTART);
                }
            }
        });
        // 小「改」按钮：不在方向键焦点链里（focusable=false），只能用鼠标点。
        // 点它才把地址框临时变成可编辑并唤起输入法。
        findViewById(R.id.btnEditAddr).setOnClickListener(v -> {
            addr.setFocusableInTouchMode(true);
            addr.setFocusable(true);
            addr.setCursorVisible(true);
            addr.requestFocus();
            addr.setSelection(addr.getText().length());
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(addr, InputMethodManager.SHOW_IMPLICIT);
        });

        // 服务端写入状态时立即刷新 UI，不再依赖 25 秒轮询
        prefListener = (sp, key) -> runOnUiThread(this::refresh);
        getSharedPreferences(MouseService.PREF, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener);

        buildParams();
        buildRows();

        // 首次启动：直接套用海信红外预设，省得用户重新学一遍
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("inited", false)) {
            restorePreset();
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("inited", true).apply();
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (prefListener != null) {
            getSharedPreferences(MouseService.PREF, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        verifyState();
    }

    /** 校验守护进程是否真的在跑，避免显示上一次会话遗留的「鼠标模式」 */
    private void verifyState() {
        if (System.currentTimeMillis() - lastVerify < 3000) return;
        lastVerify = System.currentTimeMillis();
        new Thread(() -> {
            boolean running;
            try {
                AdbKey key = new AdbKey(this);
                String[] a = MouseService.splitAddr(
                        getSharedPreferences("cfg", MODE_PRIVATE).getString("addr", "127.0.0.1:5555"));
                AdbClient c = new AdbClient(a[0], Integer.parseInt(a[1]), 2500);
                try {
                    c.handshake(key, 8000);
                    String out = c.shell("pgrep -x tvmouse || true", 4000);
                    running = out.trim().matches("\\d+");
                } finally {
                    c.close();
                }
            } catch (Throwable t) {
                runOnUiThread(() -> setAdb(false));
                return;
            }
            runOnUiThread(() -> setAdb(true));
            final boolean r = running;
            runOnUiThread(() -> {
                SharedPreferences sp = getSharedPreferences(MouseService.PREF, MODE_PRIVATE);
                String st = sp.getString("state", "idle");
                if (!r && ("active".equals(st) || "paused".equals(st))) {
                    sp.edit().putString("state", "idle")
                            .putString("detail", "detail_exited").apply();
                    refresh();
                } else if (r && "idle".equals(st)) {
                    sp.edit().putString("state", "active")
                            .putString("detail", "detail_running").apply();
                    refresh();
                }
            });
        }, "verify").start();
    }

    // ---------- 映射行 ----------

    private void buildRows() {
        mapList.removeAllViews();
        rowVals.clear();
        for (Map.Entry<String, Integer> e : ACTIONS.entrySet()) {
            final String action = e.getKey();

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.btn_bg);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> learn(action));

            TextView name = new TextView(this);
            name.setText(getString(e.getValue()));
            name.setTextColor(getResources().getColor(R.color.text_primary));
            name.setTextSize(16);
            name.setMaxLines(1);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = new TextView(this);
            val.setTextSize(15);
            val.setGravity(Gravity.END);
            val.setMaxLines(1);

            row.addView(name);
            row.addView(val);
            mapList.addView(row);
            rowVals.put(action, val);
        }
    }

    // ---------- 参数调节 ----------

    private void buildParams() {
        paramList.removeAllViews();
        paramVals.clear();
        paramList.addView(paramRow("speed", R.string.param_speed, 100, 1500));
        paramList.addView(paramRow("start", R.string.param_start, 1, 200));
        paramList.addView(paramRow("hide", R.string.param_hold, 5, 120));
    }

    private LinearLayout paramRow(String key, int labelRes, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(getString(labelRes));
        name.setTextColor(getResources().getColor(R.color.text_secondary));
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button minus = miniBtn("−");
        Button plus = miniBtn("+");
        minus.setOnClickListener(v -> adjust(key, -1, min, max));
        plus.setOnClickListener(v -> adjust(key, 1, min, max));

        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setGravity(Gravity.CENTER);
        val.setTextColor(getResources().getColor(R.color.accent));
        val.setMaxLines(1);
        val.setLayoutParams(new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT));

        row.addView(name);
        row.addView(minus);
        row.addView(val);
        row.addView(plus);
        paramVals.put(key, val);
        return row;
    }

    private Button miniBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(getResources().getColor(R.color.text_primary));
        b.setBackgroundResource(R.drawable.btn_bg);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** 步长随数值自适应：小值细调，大值快调 */
    private int stepFor(String key, int value) {
        if ("start".equals(key)) return value < 20 ? 1 : 5;
        if ("speed".equals(key)) return value < 300 ? 10 : 50;
        return 5;
    }

    private void adjust(String key, int delta, int min, int max) {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int def = "speed".equals(key) ? 300 : ("start".equals(key) ? 30 : 15);
        int cur = cfg.getInt(key, def);
        int step = stepFor(key, cur);
        int next = Math.max(min, Math.min(max, cur + (delta > 0 ? step : -step)));
        if (next == cur) return;
        cfg.edit().putInt(key, next).apply();
        refresh();
        status.removeCallbacks(restartTask);
        // 只有鼠标模式才重启让参数立即生效；遥控模式下不重启，
        // 否则每调一下都会被强行切回鼠标模式。新参数会在下次启动鼠标时生效。
        String st = getSharedPreferences(MouseService.PREF, MODE_PRIVATE).getString("state", "idle");
        if (liveRunning && "active".equals(st)) {
            status.postDelayed(restartTask, 900);
        }
    }

    private final Runnable restartTask = () -> {
        if (liveRunning) start(MouseService.ACTION_RESTART);
    };

    // ---------- 返回键 ----------

    private int backIndex() {
        String m = getSharedPreferences("cfg", MODE_PRIVATE).getString("back", "right");
        for (int i = 0; i < BACK_MODES.length; i++) if (BACK_MODES[i].equals(m)) return i;
        return 0;
    }

    private void cycleBack() {
        int next = (backIndex() + 1) % BACK_MODES.length;
        // 返回键不进映射表：映射表里的条目优先级高于 -b，会把它覆盖掉
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("back", BACK_MODES[next])
                .remove("map.right")
                .apply();
        refresh();
        if (liveRunning) {
            start(MouseService.ACTION_RELEASE);
            status.postDelayed(() -> start(MouseService.ACTION_TAKEOVER), 1500);
        }
    }

    /** 恢复海信红外遥控的已知良好配置 */
    private void restorePreset() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putInt("map.left", 28)
                .putInt("speed", 300)
                .putInt("start", 30)
                .putInt("hide", 15)
                .putInt("map.wheelup", 660)     // 直播键
                .putInt("map.wheeldown", 467)   // 设置键
                .putInt("map.home", 102)
                .putInt("map.toggle", 687)
                .remove("map.middle")
                .remove("map.right")
                .remove("map.back")
                .putString("back", "right")
                .apply();
        Toast.makeText(this, R.string.toast_restored, Toast.LENGTH_SHORT).show();
        refresh();
        if (liveRunning) {
            start(MouseService.ACTION_RELEASE);
            status.postDelayed(() -> start(MouseService.ACTION_TAKEOVER), 1500);
        }
    }

    // ---------- 按键学习 ----------

    private void learn(String action) {
        setStatus("learning", "detail_learning");
        Toast.makeText(this, R.string.detail_learning, Toast.LENGTH_SHORT).show();
        start(MouseService.ACTION_RELEASE);

        new Thread(() -> {
            final int[] found = {0};
            try {
                Thread.sleep(1200);
                AdbKey key = new AdbKey(this);
                String[] a = MouseService.splitAddr(
                        getSharedPreferences("cfg", MODE_PRIVATE).getString("addr", "127.0.0.1:5555"));
                AdbClient c = new AdbClient(a[0], Integer.parseInt(a[1]), 3000);
                try {
                    c.handshake(key, 20000);
                    final StringBuilder acc = new StringBuilder();
                    c.shell("/data/local/tmp/tvmouse -l", 6000, chunk -> {
                        if (found[0] > 0) return;
                        acc.append(chunk);
                        String s = acc.toString();
                        int i = s.indexOf("code=");
                        if (i >= 0) {
                            int j = i + 5, k = j;
                            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                            if (k > j) {
                                try { found[0] = Integer.parseInt(s.substring(j, k)); } catch (Exception ignored) { }
                            }
                        }
                    });
                } finally {
                    c.close();
                }
            } catch (Throwable ignored) { }

            final int code = found[0];
            // 所有 UI 与 SharedPreferences 操作都回到主线程
            runOnUiThread(() -> {
                if (code == 158) {
                    setStatus("learn_fail", "detail_back_reserved");
                } else if (code > 0) {
                    SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
                    SharedPreferences.Editor ed = cfg.edit();
                    for (String other : ACTIONS.keySet()) {
                        if (!other.equals(action) && cfg.getInt("map." + other, 0) == code) {
                            ed.remove("map." + other);
                        }
                    }
                    ed.putInt("map." + action, code).apply();
                    setStatus("connecting", "detail_connecting");
                } else {
                    setStatus("learn_fail", "detail_learn_fail");
                }
                refresh();
                start(MouseService.ACTION_TAKEOVER);
            });
        }).start();
    }

    // ---------- 状态 ----------

    private void start(String action) {
        Intent i = new Intent(this, MouseService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        pollLeft = 25;
        status.removeCallbacks(poll);
        status.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (pollLeft-- > 0) status.postDelayed(this, 1000);
        }
    };

    private void setStatus(String stateKey, String detailKey) {
        getSharedPreferences(MouseService.PREF, MODE_PRIVATE).edit()
                .putString("state", stateKey).putString("detail", detailKey).apply();
        refresh();
    }

    /** 状态键 → 显示文字 */
    private int stateRes(String key) {
        switch (key) {
            case "active": return R.string.state_mouse;
            case "connecting": return R.string.state_connecting;
            case "waiting": return R.string.state_waiting;
            case "learning": return R.string.state_learning;
            case "fail":
            case "learn_fail": return R.string.state_fail;
            default: return R.string.state_remote;   // idle / paused / released
        }
    }

    /** 详情键 → 显示文字，未知键返回 0 */
    private int detailRes(String key) {
        if (key == null) return 0;
        switch (key) {
            case "detail_released": return R.string.detail_released;
            case "detail_waiting": return R.string.detail_waiting;
            case "detail_connecting": return R.string.detail_connecting;
            case "detail_running": return R.string.detail_running;
            case "detail_paused": return R.string.detail_paused;
            case "detail_exited": return R.string.detail_exited;
            case "detail_learning": return R.string.detail_learning;
            case "detail_learn_fail": return R.string.detail_learn_fail;
            case "detail_back_reserved": return R.string.detail_back_reserved;
            default: return 0;
        }
    }

    private void setAdb(boolean ok) {
        if (adbState == null) return;
        adbState.setText(ok ? R.string.adb_connected : R.string.adb_disconnected);
        adbState.setTextColor(getResources().getColor(ok ? R.color.accent : R.color.danger));
    }

    private void refresh() {
        SharedPreferences sp = getSharedPreferences(MouseService.PREF, MODE_PRIVATE);
        String state = sp.getString("state", "idle");
        String detail = sp.getString("detail", "");
        liveRunning = "active".equals(state) || "paused".equals(state);

        status.setText(stateRes(state));
        int colorRes;
        if ("active".equals(state)) colorRes = R.color.accent;
        else if ("fail".equals(state) || "learn_fail".equals(state)) colorRes = R.color.danger;
        else if ("connecting".equals(state) || "waiting".equals(state) || "learning".equals(state))
            colorRes = R.color.warn;
        else colorRes = R.color.text_secondary;
        status.setTextColor(getResources().getColor(colorRes));

        // 绿底跟随状态：鼠标模式时启动键亮，否则释放键亮
        boolean on = "active".equals(state);
        btnTakeover.setBackgroundResource(on ? R.drawable.btn_primary_bg : R.drawable.btn_bg);
        btnTakeover.setTextColor(getResources().getColor(on ? R.color.accent : R.color.text_primary));
        btnRelease.setBackgroundResource(on ? R.drawable.btn_bg : R.drawable.btn_primary_bg);
        btnRelease.setTextColor(getResources().getColor(on ? R.color.text_primary : R.color.accent));

        backBtn.setText(getString(R.string.back_prefix) + getString(BACK_LABELS[backIndex()]));
        ((Button) findViewById(R.id.btnLang)).setText(
                getString(R.string.lang_prefix) + getString(LANG_LABELS[langIndex()]));

        // 版本号与关于
        try {
            ((TextView) findViewById(R.id.ver)).setText(
                    "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Throwable ignored) { }

        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);

        TextView sp1 = paramVals.get("speed");
        if (sp1 != null) sp1.setText(cfg.getInt("speed", 300) + " " + getString(R.string.unit_speed));
        TextView sp2 = paramVals.get("start");
        if (sp2 != null) sp2.setText(cfg.getInt("start", 30) + " " + getString(R.string.unit_speed));
        TextView sp3 = paramVals.get("hide");
        if (sp3 != null) sp3.setText(cfg.getInt("hide", 15) + " " + getString(R.string.unit_sec));

        for (Map.Entry<String, Integer> e : ACTIONS.entrySet()) {
            TextView v = rowVals.get(e.getKey());
            if (v == null) continue;
            int code = cfg.getInt("map." + e.getKey(), 0);
            // 返回键当前承担鼠标右键时，把 158 显示到「鼠标右键」这一行
            if (code <= 0 && "right".equals(e.getKey())
                    && "right".equals(cfg.getString("back", "right"))) {
                code = 158;
            }
            if (code <= 0) {
                v.setText(R.string.not_set);
                v.setTextColor(getResources().getColor(R.color.text_muted));
            } else {
                String n = codeName(code);
                v.setText(n == null ? String.valueOf(code) : code + " · " + n);
                v.setTextColor(getResources().getColor(R.color.accent));
            }
        }

        // 遥控示意图：鼠标模式下把已映射的键换成鼠标动作
        if (remote != null) {
            Map<Integer, String> labels = new LinkedHashMap<>();
            putLabel(cfg, labels, "left", R.string.mouse_left);
            putLabel(cfg, labels, "wheelup", R.string.mouse_wheelup);
            putLabel(cfg, labels, "wheeldown", R.string.mouse_wheeldown);
            putLabel(cfg, labels, "home", R.string.key_home);
            putLabel(cfg, labels, "toggle", R.string.mouse_toggle);
            String bm = cfg.getString("back", "right");
            if (!"back".equals(bm)) {
                labels.put(158, getString("side".equals(bm) ? R.string.mouse_middle : R.string.mouse_right));
            }
            remote.setState("active".equals(state), labels);
        }
    }

    /** 语言：自动 → 中文 → English → 自动 */
    private static final String[] LANGS = {"auto", "zh", "en"};
    private static final int[] LANG_LABELS = {
            R.string.lang_auto, R.string.lang_zh, R.string.lang_en};

    private int langIndex() {
        String v = getSharedPreferences("cfg", MODE_PRIVATE).getString("lang", "auto");
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(v)) return i;
        return 0;
    }

    private void cycleLang() {
        int next = (langIndex() + 1) % LANGS.length;
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("lang", LANGS[next]).apply();
        recreate();   // 重新创建 Activity，让 attachBaseContext 生效
    }

    /** 按用户选择覆盖资源语言（API 21 没有 per-app locale，只能自己包一层 Context） */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        String lang = base.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                .getString("lang", "auto");
        if ("auto".equals(lang)) {
            super.attachBaseContext(base);
            return;
        }
        java.util.Locale l = "en".equals(lang)
                ? java.util.Locale.ENGLISH : java.util.Locale.SIMPLIFIED_CHINESE;
        android.content.res.Configuration c =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        c.setLocale(l);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    /** 关于弹窗：版本、日期、用途、许可证、发布说明 */
    private void showAbout() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) { }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, v, BuildInfo.DATE))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** 已知键码 → 键名，显示在映射表里避免用户看不懂数字 */
    private String codeName(int code) {
        switch (code) {
            case 28: return "OK";
            case 116: return getString(R.string.key_power);
            case 660: return getString(R.string.key_live);
            case 687: return getString(R.string.key_app);
            case 467: return getString(R.string.key_settings);
            case 158: return getString(R.string.key_back);
            case 102: return getString(R.string.key_home);
            case 139: return getString(R.string.key_menu);
            case 115: return getString(R.string.key_volume) + "+";
            case 114: return getString(R.string.key_volume) + "-";
            default: return null;
        }
    }

    private void putLabel(SharedPreferences cfg, Map<Integer, String> m, String key, int labelRes) {
        int code = cfg.getInt("map." + key, 0);
        if (code > 0) m.put(code, getString(labelRes));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
