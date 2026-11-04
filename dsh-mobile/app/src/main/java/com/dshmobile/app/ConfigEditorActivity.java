package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * dsh 配置文件（容器内 /home/dsh/.dsh/settings.yaml）的原生编辑器。
 * dsh web 桌面的「打开配置文件」调 xdg-open/编辑器，容器里没有必然失败；
 * 网页侧由 inject.js 拦截按钮点击改走 JS 桥打开本页面。
 * dsh-settings-file 用 chokidar 监听该文件，保存后自动热加载，无需重启服务。
 */
public class ConfigEditorActivity extends Activity {

    private EditText editor;
    private TextView status;
    private File configFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        configFile = new File(ProotRunner.homeDir(this), ".dsh/settings.yaml");
        buildUi();
        loadFile();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Ui.bg(this));

        TextView title = Ui.title(this, "Configuration file");
        root.addView(title);

        TextView path = Ui.hint(this, configFile.getAbsolutePath());
        LinearLayout.LayoutParams plp = Ui.matchWrap();
        plp.topMargin = Ui.dp(this, 4);
        root.addView(path, plp);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setTextColor(Ui.text(this));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setBackgroundColor(Ui.bgSoft(this));
        int epad = Ui.dp(this, 12);
        editor.setPadding(epad, epad, epad, epad);
        ScrollView sv = new ScrollView(this);
        sv.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        root.addView(sv, slp);

        status = Ui.hint(this, "");
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 8);
        root.addView(status, stlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = Ui.matchWrap();
        blp.topMargin = Ui.dp(this, 8);
        Button cancel = Ui.outlineButton(this, "Back");
        cancel.setOnClickListener(v -> finish());
        btns.addView(cancel);
        Button save = Ui.primaryButton(this, "Save");
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        s2.leftMargin = Ui.dp(this, 16);
        save.setLayoutParams(s2);
        save.setOnClickListener(v -> saveFile());
        btns.addView(save);
        root.addView(btns, blp);

        setContentView(root);
    }

    private void loadFile() {
        if (!configFile.isFile()) {
            // 文件不存在时给一份带注释的空文档（dsh 的 prepareDocument 也是给空文件）
            editor.setText("# dsh settings\n# See the Settings dialog for namespace options. Changes are hot-reloaded automatically.\n");
            status.setText("The file does not exist yet. It will be created when you save.");
            return;
        }
        try {
            FileInputStream in = new FileInputStream(configFile);
            byte[] buf = new byte[(int) Math.min(configFile.length(), 1024 * 1024)];
            int n = in.read(buf);
            in.close();
            editor.setText(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            status.setText("Read failed: " + e.getMessage());
        }
    }

    /** 写临时文件再 rename（原子替换），避免 dsh 的 watcher 读到半截内容。 */
    private void saveFile() {
        try {
            File dir = configFile.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(configFile.getParentFile(), "settings.yaml.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(configFile)) {
                throw new IOException("rename failed");
            }
            status.setText("Saved. DSH will reload the changes automatically; no restart is needed.");
            Toast.makeText(this, "Configuration saved.", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            status.setText("Save failed: " + e.getMessage());
        }
    }
}
