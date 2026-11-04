package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 容器终端：在 proot Ubuntu 里跑一个持久 bash（管道收发，非 PTY）。
 * cd / 环境变量等状态在会话内保持；命令卡死时点「重启」杀掉整个 shell 重来。
 */
public class TerminalActivity extends Activity {

    private static final int BG = Color.parseColor("#0D1117");
    private static final int PANEL = Color.parseColor("#161B22");
    private static final int FG = Color.parseColor("#E6EDF3");
    private static final int FG_DIM = Color.parseColor("#8B949E");
    private static final int ACCENT = Color.parseColor("#58A6FF");
    private static final int DIVIDER = Color.parseColor("#30363D");
    /** 输出缓冲上限：超出后丢弃早期内容，防长任务刷屏撑爆内存。 */
    private static final int MAX_BUFFER = 200_000;
    private static final int KEEP_BUFFER = 150_000;

    private final StringBuilder buffer = new StringBuilder();
    private final Object lock = new Object();
    private Handler handler;
    private ScrollView scroll;
    private TextView output;
    private EditText input;
    private Process shell;
    private OutputStream stdin;
    private boolean flushPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());
        buildUi();
        startShell();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 顶栏：标题 + 清屏/重启
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int bp = Ui.dp(this, 8);
        bar.setPadding(bp, bp, bp, bp);
        TextView title = new TextView(this);
        title.setText("Container terminal");
        title.setTextColor(FG);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(action("Clear", v -> {
            synchronized (lock) {
                buffer.setLength(0);
            }
            output.setText("");
        }));
        bar.addView(action("Restart", v -> startShell()));
        root.addView(bar);
        View div = new View(this);
        div.setBackgroundColor(DIVIDER);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 0.5f)));

        // 输出区
        output = new TextView(this);
        output.setTextColor(FG);
        output.setTextSize(12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        int op = Ui.dp(this, 10);
        output.setPadding(op, op, op, op);
        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 输入行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(PANEL);
        int rp = Ui.dp(this, 8);
        row.setPadding(rp, rp, rp, rp);
        input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(FG_DIM);
        input.setHint("Enter a command and press Enter to run");
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(null);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            send();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(action("Run", v -> send()));
        root.addView(row);

        setContentView(root);
    }

    private TextView action(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ACCENT);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int h = Ui.dp(this, 12);
        int v = Ui.dp(this, 6);
        tv.setPadding(h, v, h, v);
        tv.setOnClickListener(click);
        return tv;
    }

    private void startShell() {
        stopShell();
        if (!ProotRunner.prootBin(this).isFile() || !ProotRunner.rootfsDir(this).isDirectory()) {
            append("容器尚未安装，请先完成初始化。\n");
            return;
        }
        try {
            shell = ProotRunner.execPiped(this, Arrays.asList("/bin/bash", "--noprofile", "--norc"));
            stdin = shell.getOutputStream();
            Process p = shell;
            new Thread(() -> readLoop(p), "dsh-term-read").start();
            append("已连接容器 shell（工作目录 /home/dsh；命令卡死可点「重启」中断）\n");
        } catch (IOException e) {
            append("Failed to start shell: " + e.getMessage() + "\n");
        }
    }

    private void stopShell() {
        if (shell != null) {
            // proot 带 --kill-on-exit，destroy 后容器内进程树一并退出
            shell.destroy();
            shell = null;
        }
        stdin = null;
    }

    private void readLoop(Process p) {
        try (InputStreamReader r =
                     new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                append(new String(buf, 0, n));
            }
        } catch (IOException ignored) {
        }
        // 只有当前会话自然退出才提示（被「重启」替换掉的旧会话不刷提示）
        if (p == shell) {
            append("\n[会话已结束，点「重启」重新连接]\n");
        }
    }

    private void send() {
        String cmd = input.getText().toString();
        input.setText("");
        OutputStream out = stdin;
        if (shell == null || out == null || !shell.isAlive()) {
            append("（shell 未连接，点右上角「重启」）\n");
            return;
        }
        if (!cmd.isEmpty()) {
            append("$ " + cmd + "\n");
        }
        new Thread(() -> {
            try {
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                handler.post(() -> append("[Write failed: " + e.getMessage() + "]\n"));
            }
        }, "dsh-term-write").start();
    }

    private void append(String s) {
        synchronized (lock) {
            buffer.append(s);
            if (buffer.length() > MAX_BUFFER) {
                buffer.delete(0, buffer.length() - KEEP_BUFFER);
                buffer.insert(0, "…（早期输出已截断）\n");
            }
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushPending) return;
        flushPending = true;
        handler.post(() -> {
            flushPending = false;
            synchronized (lock) {
                output.setText(buffer.toString());
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        stopShell();
        super.onDestroy();
    }
}
