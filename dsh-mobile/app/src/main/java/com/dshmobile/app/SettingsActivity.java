package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 设置：SD 映射、端口、镜像、日志、服务控制、重置。DeepSeek 风格。 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bgSoft(this));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        fillRows();
    }

    private void fillRows() {
        TextView pageTitle = Ui.title(this, "Settings");
        LinearLayout.LayoutParams tlp = Ui.matchWrap();
        tlp.bottomMargin = Ui.dp(this, 4);
        list.addView(pageTitle, tlp);

        // ---- SD 卡映射 ----
        addHeader("SD card mapping (container /mnt/sd)");
        LinearLayout sdCard = Ui.card(this);
        boolean allFiles = Environment.isExternalStorageManager();
        addRow(sdCard, "All files access permission",
                allFiles ? "Granted ✓" : "Not granted. Tap here to enable.",
                v -> {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }
                });
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, "External SD card directory", sdDisplay(), v -> pickSdDir());
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, "Shared storage fallback", "/sdcard/dsh-shared → /mnt/shared (created automatically)", null);
        list.addView(sdCard, cardLp());

        // ---- 服务 ----
        addHeader("Service");
        LinearLayout svcCard = Ui.card(this);
        addRow(svcCard, "Web port", String.valueOf(prefs.getPort()),
                v -> editText("Web port", String.valueOf(prefs.getPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setPort(p);
                            toast("Port saved. Restart the service to apply it.");
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast("Invalid port.");
                    }
                }));
        svcCard.addView(Ui.divider(this));
        addRow(svcCard, "Service status", HarnessService.isRunning() ? "Running" : "Stopped", null);
        list.addView(svcCard, cardLp());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle = Ui.primaryButton(this, HarnessService.isRunning() ? "Stop service" : "Start service");
        toggle.setOnClickListener(v -> {
            if (HarnessService.isRunning()) {
                HarnessService.stopService(this);
            } else {
                HarnessService.startService(this);
            }
            list.postDelayed(this::refresh, 800);
        });
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        btnRow.addView(toggle, tbp);
        Button restart = Ui.outlineButton(this, "Restart service");
        restart.setOnClickListener(v -> {
            HarnessService.stopService(this);
            // 停止在独立线程执行（强杀兜底最长 ~3s），等旧容器确实死掉再启动，
            // 否则启动时 isRunning() 仍为 true，runLoop 不会被拉起，服务假死
            list.postDelayed(() -> {
                HarnessService.startService(this);
                refresh();
            }, 4000);
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        rbp.leftMargin = Ui.dp(this, 12);
        btnRow.addView(restart, rbp);
        LinearLayout.LayoutParams brlp = Ui.matchWrap();
        brlp.topMargin = Ui.dp(this, 12);
        list.addView(btnRow, brlp);

        // ---- 容器 SSH ----
        addHeader("Container SSH (starts with the service)");
        LinearLayout sshCard = Ui.card(this);
        addRow(sshCard, "Connection",
                "On this device (Terminal/Termux): ssh dsh@127.0.0.1 -p " + prefs.getSshPort()
                        + " (regular user; PATH includes node/npm. Root can also log in with the same password); on a computer: adb forward tcp:"
                        + prefs.getSshPort() + " tcp:" + prefs.getSshPort(),
                null);
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, "Username / password", "dsh / " + prefs.getSshPassword() + " (tap to copy password)", v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ssh", prefs.getSshPassword()));
            toast("Password copied.");
        });
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, "SSH port", String.valueOf(prefs.getSshPort()) + " (change this if it conflicts with Termux)",
                v -> editText("SSH port", String.valueOf(prefs.getSshPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setSshPort(p);
                            toast("SSH port saved. Restart the service to apply it.");
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast("Invalid port.");
                    }
                }));
        list.addView(sshCard, cardLp());

        // ---- 后台保活 ----
        // 划卡清任务被杀是 OEM 行为（荣耀/MagicOS 默认杀整进程），App 侧只能
        // 尽量降低被杀概率：电池优化白名单 + 引导用户开「自启动/允许后台活动」。
        addHeader("Background operation (prevent the service from being killed)");
        LinearLayout keepCard = Ui.card(this);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        addRow(keepCard, "Ignore battery optimizations",
                ignoring ? "Whitelisted ✓" : "Not whitelisted. Tap here to enable.",
                v -> {
                    android.os.PowerManager p = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (p != null && !p.isIgnoringBatteryOptimizations(getPackageName())) {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            toast("Could not open battery optimization settings.");
                        }
                    }
                });
        keepCard.addView(Ui.divider(this));
        addRow(keepCard, "Auto-start / allow background activity",
                "Some devices require this to be enabled manually. Tap to open app settings, then allow auto-start and background activity.",
                v -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Could not open app details.");
                    }
                });
        list.addView(keepCard, cardLp());

        // ---- 镜像 ----
        addHeader("Download mirrors (applies to the next installation)");
        LinearLayout mirrorCard = Ui.card(this);
        addRow(mirrorCard, "Root filesystem URL", prefs.getRootfsUrl(),
                v -> editText("Root filesystem URL", prefs.getRootfsUrl(), t -> {
                    prefs.setRootfsUrl(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, "Node.js mirror", prefs.getNodeMirror(),
                v -> editText("Node.js mirror", prefs.getNodeMirror(), t -> {
                    prefs.setNodeMirror(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, "npm registry", prefs.getNpmRegistry(),
                v -> editText("npm registry", prefs.getNpmRegistry(), t -> {
                    prefs.setNpmRegistry(t.trim());
                    refresh();
                }));
        list.addView(mirrorCard, cardLp());

        // ---- 维护 ----
        addHeader("Maintenance");
        Button fmBtn = Ui.outlineButton(this, "File manager (import/export)");
        fmBtn.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)));
        list.addView(fmBtn, btnLp());
        Button termBtn = Ui.outlineButton(this, "Open container terminal");
        termBtn.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));
        list.addView(termBtn, btnLp());
        Button logBtn = Ui.outlineButton(this, "View service log");
        logBtn.setOnClickListener(v -> showLog("dsh-web.log"));
        list.addView(logBtn, btnLp());
        Button installLogBtn = Ui.outlineButton(this, "View installation log");
        installLogBtn.setOnClickListener(v -> showLog("install.log"));
        list.addView(installLogBtn, btnLp());
        Button resetBtn = Ui.outlineButton(this, "Reset container (delete all data)");
        resetBtn.setTextColor(0xFFE54545);
        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset container")
                .setMessage("This will delete the Ubuntu container and all its data. It will be installed again the next time you start the app. Continue?")
                .setPositiveButton("Reset", (d, w) -> {
                    HarnessService.stopService(this);
                    new Thread(() -> {
                        BootstrapInstaller.deleteRecursively(ProotRunner.baseDir(this));
                        prefs.setSetupDone(false);
                        runOnUiThread(() -> {
                            startActivity(new Intent(this, SetupActivity.class));
                            finish();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show());
        list.addView(resetBtn, btnLp());
    }

    private String sdDisplay() {
        String sd = prefs.getSdPath();
        if (sd == null) return "Not set (using /mnt/shared only)";
        return sd + (new File(sd).isDirectory() ? "" : " (path does not exist!)");
    }

    private void pickSdDir() {
        if (!Environment.isExternalStorageManager()) {
            toast("Please enable “All files access” first.");
            return;
        }
        List<String> options = new ArrayList<>();
        options.add("Do not map (use /mnt/shared only)");
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v.isDirectory() && !v.getName().equals("emulated") && !v.getName().equals("self")) {
                    options.add(v.getAbsolutePath());
                }
            }
        }
        options.add("Enter path manually…");
        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Choose external SD card directory")
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (which == 0) {
                        prefs.setSdPath(null);
                        refresh();
                    } else if (sel.startsWith("/storage")) {
                        String dir = sel + "/dsh";
                        //noinspection ResultOfMethodCallIgnored
                        new File(dir).mkdirs();
                        prefs.setSdPath(dir);
                        toast("Mapped " + dir + " → /mnt/sd. Restart the service to apply it.");
                        refresh();
                    } else {
                        editText("Full SD card directory path",
                                prefs.getSdPath() == null ? "" : prefs.getSdPath(),
                                t -> {
                                    prefs.setSdPath(t.trim());
                                    refresh();
                                });
                    }
                })
                .show();
    }

    private void showLog(String name) {
        File f = new File(ProotRunner.baseDir(this), name);
        StringBuilder sb = new StringBuilder();
        if (f.isFile()) {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                int from = Math.max(0, all.length - 60_000);
                sb.append(new String(all, from, all.length - from));
            } catch (Exception e) {
                sb.append("Read failed: ").append(e.getMessage());
            }
        } else {
            sb.append("(No log available yet)");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private void editText(String title, String current, OnText cb) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        int p = Ui.dp(this, 16);
        et.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("Save", (d, w) -> cb.accept(et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface OnText {
        void accept(String t);
    }

    private void addHeader(String text) {
        TextView tv = Ui.sectionHeader(this, text);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 20);
        lp.bottomMargin = Ui.dp(this, 8);
        list.addView(tv, lp);
    }

    private void addRow(LinearLayout parent, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView t = Ui.body(this, title);
        row.addView(t);
        if (subtitle != null) {
            TextView s = Ui.hint(this, subtitle);
            s.setTextSize(12);
            row.addView(s);
        }
        if (click != null) {
            row.setOnClickListener(click);
        }
        parent.addView(row, Ui.matchWrap());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        return lp;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 10);
        return lp;
    }

    private void refresh() {
        list.removeAllViews();
        fillRows();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
