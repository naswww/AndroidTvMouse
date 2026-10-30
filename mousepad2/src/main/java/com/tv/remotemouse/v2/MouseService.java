package com.tv.remotemouse.v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 接管服务：用内建 adb 客户端连本机 adbd，把守护脚本推到 /data/local/tmp 并启动。
 *
 * 注意：本 ROM 的 adbd 在连接断开时会杀掉 shell 的整个进程树（setsid 也挡不住），
 * 所以这里**保持 adb 连接常驻**——连接在，守护进程就在；释放时断开连接即可。
 */
public class MouseService extends Service {

    static final String TAG = "RemoteMouse";
    static final String ACTION_TAKEOVER = "com.tv.remotemouse.TAKEOVER";
    static final String ACTION_RELEASE = "com.tv.remotemouse.RELEASE";
    static final String ACTION_RESTART = "com.tv.remotemouse.RESTART";
    static final String CH_ID = "mouse";
    static final String PREF = "status";

    private volatile AdbClient live;
    private Thread worker;
    private volatile boolean quiet = false;   // 调参数触发的静默重启，不显示中间状态

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(1, buildNotification(getString(R.string.app_name)));
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? ACTION_TAKEOVER : intent.getAction();

        if (ACTION_RELEASE.equals(action)) {
            final AdbClient old = live;
            final Thread prev = worker;
            new Thread(() -> {
                try { if (old != null) old.close(); } catch (Exception ignored) { }
                if (prev != null) { try { prev.join(1500); } catch (InterruptedException ignored) { } }
                release();
                setStatus("released", "detail_released");
                stopSelf();
            }, "adb-release").start();
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART.equals(action)) quiet = true;   // 调参数触发的重启，不显示中间状态

        // 断开旧连接、等旧线程退出都放到后台线程，避免阻塞主线程导致 ANR
        final AdbClient oldLive = live;
        final Thread prev = worker;
        worker = new Thread(() -> {
            try { if (oldLive != null) oldLive.close(); } catch (Exception ignored) { }
            if (prev != null && prev != Thread.currentThread()) {
                try { prev.join(1500); } catch (InterruptedException ignored) { }
            }
            takeover();
        }, "adb-worker");
        worker.start();
        return START_STICKY;
    }

    /** 连接 adbd；开机时 adbd 可能还没就绪，重试约 2 分钟。 */
    /** 读取用户配置的 adb 地址，默认 127.0.0.1:5555 */
    static String[] splitAddr(String raw) {
        String a = raw == null ? "" : raw.trim();
        if (a.isEmpty()) a = "127.0.0.1:5555";
        int i = a.lastIndexOf(':');
        if (i <= 0 || i == a.length() - 1) return new String[]{a, "5555"};
        return new String[]{a.substring(0, i), a.substring(i + 1)};
    }

    private String[] addr() {
        return splitAddr(getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("addr", "127.0.0.1:5555"));
    }

    private AdbClient connectWithRetry(AdbKey key) throws Exception {
        Exception last = null;
        for (int i = 0; i < 24; i++) {
            AdbClient c = null;
            try {
                String[] a = addr();
                c = new AdbClient(a[0], Integer.parseInt(a[1]), 3000);
                c.handshake(key, 60000);
                return c;
            } catch (Exception e) {
                last = e;
                if (c != null) c.close();
                Log.w(TAG, "connect attempt " + (i + 1) + " failed: " + e);
                if (i == 0) setStatus("waiting", "detail_waiting");
                Thread.sleep(5000);
            }
        }
        throw last == null ? new Exception("无法连接 adbd") : last;
    }

    private void takeover() {
        AdbClient c = null;
        try {
            if (!quiet) setStatus("connecting", "detail_connecting");
            quiet = false;
            AdbKey key = new AdbKey(this);
            c = connectWithRetry(key);

            // 推原生守护进程（单进程完成读-判-写，比 shell 版快两个数量级）
            c.shell("echo '" + assetBase64("tvmouse.bin") + "' | base64 -d > /data/local/tmp/tvmouse"
                    + " && chmod 755 /data/local/tmp/tvmouse", 20000);
            // 推按键映射表
            String map = buildMap();
            if (map.length() > 0) {
                String b64 = Base64.encodeToString(map.getBytes("UTF-8"), Base64.NO_WRAP);
                c.shell("echo '" + b64 + "' | base64 -d > /data/local/tmp/tvmouse.map", 10000);
            } else {
                c.shell("rm -f /data/local/tmp/tvmouse.map", 5000);
            }
            // 清掉可能残留的旧实例
            c.shell("pkill -f /data/local/tmp/tvmouse; pkill -f tvmouse.sh; true", 8000);

            live = c;
            setStatus("active", "detail_running");

            boolean grab = true;   // 始终独占，用切换键在"鼠标/遥控"之间切换
            String back = getSharedPreferences("cfg", MODE_PRIVATE).getString("back", "right");
            SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
            int toggle = cfg.getInt("map.toggle", 0);
            int speed = cfg.getInt("speed", 300);
            int start = cfg.getInt("start", 30);
            int hide = cfg.getInt("hide", 15);
            // 不使用 -p 回放模式：暂停态直接放开独占，遥控恢复原生。
            // 切换键在「遥控→鼠标」时可能让电视弹一次 U 模式提示，可忽略；
            // 换来的是两个方向都能可靠切换。
            // 屏幕尺寸用于滚轮贴边补偿：指针贴顶/贴底时先往中间挪 1/4 屏
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            String cmd = "/data/local/tmp/tvmouse -s " + speed + " -i " + start + " -h " + hide + " -b " + back
                    + " -W " + sw + " -H " + sh
                    + (toggle > 0 ? " -t " + toggle : "")
                    + (grab ? " -g" : "");
            // 前台挂着跑，连接不断，守护进程就不断
            c.shell(cmd, 0, chunk -> {
                if (chunk.contains("ERR=no_mouse")) setStatus("fail", "detail_no_mouse");
                else if (chunk.contains("ERR=grab_failed")) setStatus("fail", "detail_grab_failed");
                else if (chunk.contains("STATE=PAUSED")) setStatus("paused", "detail_paused");
                else if (chunk.contains("STATE=ACTIVE")) setStatus("active", "detail_running");
                else if (chunk.contains("pt=")) setStatus("active", "detail_running");
            });

            setStatus("idle", "detail_exited");
        } catch (Throwable t) {
            setStatus("fail", "detail_exited");
            Log.e(TAG, "takeover failed", t);
        } finally {
            // 只有当前连接仍是自己时才关闭，避免旧线程把新连接掐掉
            if (c != null && live == c) {
                try { c.close(); } catch (Exception ignored) { }
                live = null;
            }
        }
    }

    private void release() {
        try {
            AdbClient c = live;
            if (c != null) {
                c.close();     // 断开连接 → adbd 回收 shell → 守护进程随之退出
                live = null;
            }
            // 再补一刀，保证停干净
            AdbKey key = new AdbKey(this);
            String[] a = addr();
            AdbClient k = new AdbClient(a[0], Integer.parseInt(a[1]), 3000);
            try {
                k.handshake(key, 15000);
                k.shell("pkill -f /data/local/tmp/tvmouse; pkill -f tvmouse.sh; true", 8000);
            } finally {
                k.close();
            }
        } catch (Throwable ignored) { }
    }

    private String assetBase64(String name) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    /** 把 App 里保存的按键映射拼成守护进程能读的文本：`键码=动作` */
    private String buildMap() {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        String[] acts = {"left", "right", "wheelup", "wheeldown", "home"};   // 返回键(158)由 -b 管，不进映射表
        StringBuilder sb = new StringBuilder();
        for (String a : acts) {
            int code = cfg.getInt("map." + a, 0);
            if (code > 0) sb.append(code).append('=').append(a).append('\n');
        }
        return sb.toString();
    }

    private void setStatus(String state, String detail) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("state", state)
                .putString("detail", detail)
                .putLong("time", System.currentTimeMillis())
                .apply();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification(state));
        } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
