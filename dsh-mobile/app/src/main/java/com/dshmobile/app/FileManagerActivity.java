package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 容器文件管理器：rootfs 就是 app 私有目录下的真实文件树，Java 侧直接读写。
 * 路径以容器内视角显示（/ = rootfs 根）；默认从 /home/dsh（dsh 工作目录）开始。
 * 导入走 SAF（免权限），导出复制到 /sdcard/dsh-shared（即容器 /mnt/shared）。
 */
public class FileManagerActivity extends Activity {

    private static final int REQ_IMPORT = 41;

    private File rootfs;
    private File current;
    private TextView pathView;
    private ArrayAdapter<String> adapter;
    /** 与 adapter 行一一对应；row 0 可能是「..」返回行（对应 null）。 */
    private final List<File> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootfs = ProotRunner.rootfsDir(this);
        if (!rootfs.isDirectory()) {
            Toast.makeText(this, "The container is not installed. Please complete setup first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        File home = ProotRunner.homeDir(this);
        current = home.isDirectory() ? home : rootfs;
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bgSoft(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int bp = Ui.dp(this, 12);
        bar.setPadding(bp, Ui.dp(this, 10), bp, Ui.dp(this, 4));
        bar.setBackgroundColor(Ui.bg(this));
        TextView title = Ui.title(this, "File manager");
        title.setTextSize(18);
        bar.addView(title);
        pathView = Ui.hint(this, "");
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        bar.addView(pathView);
        root.addView(bar);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Ui.text(v.getContext()));
                tv.setTextSize(15);
                tv.setPadding(Ui.dp(FileManagerActivity.this, 16),
                        Ui.dp(FileManagerActivity.this, 12),
                        Ui.dp(FileManagerActivity.this, 16),
                        Ui.dp(FileManagerActivity.this, 12));
                return v;
            }
        };
        ListView listView = new ListView(this);
        listView.setBackgroundColor(Ui.bg(this));
        listView.setDivider(null);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onTap(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> onLongTap(pos));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底栏
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Ui.bg(this));
        bottom.setPadding(bp, bp, bp, bp);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button importBtn = Ui.primaryButton(this, "Import to current folder");
        importBtn.setOnClickListener(v -> pickImport());
        btnRow.addView(importBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        android.widget.Button mkdirBtn = Ui.outlineButton(this, "New folder");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        mlp.leftMargin = Ui.dp(this, 12);
        mkdirBtn.setLayoutParams(mlp);
        mkdirBtn.setOnClickListener(v -> mkdirDialog());
        btnRow.addView(mkdirBtn);
        bottom.addView(btnRow);
        TextView hint = Ui.hint(this, "Tap a file to export it to /mnt/shared (/sdcard/dsh-shared). Long-press to delete.");
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.topMargin = Ui.dp(this, 8);
        bottom.addView(hint, hlp);
        root.addView(bottom);

        setContentView(root);
    }

    /** 容器视角路径（/ = rootfs）。 */
    private String containerPath(File f) {
        String r = rootfs.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) return "/";
        return p.startsWith(r + "/") ? p.substring(r.length()) : p;
    }

    private void refresh() {
        pathView.setText(containerPath(current));
        rows.clear();
        adapter.clear();
        if (!current.equals(rootfs)) {
            rows.add(null);
            adapter.add("..  (Go up)");
        }
        File[] kids = current.listFiles();
        if (kids != null) {
            Arrays.sort(kids, Comparator
                    .comparing(File::isFile) // 目录在前
                    .thenComparing(f -> f.getName().toLowerCase()));
            for (File f : kids) {
                rows.add(f);
                adapter.add(f.isDirectory()
                        ? "\uD83D\uDCC1 " + f.getName() + "/"
                        : "\uD83D\uDCC4 " + f.getName() + "    " + sizeText(f.length()));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static String sizeText(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }

    private void onTap(int pos) {
        File f = rows.get(pos);
        if (f == null) { // ".."
            current = current.getParentFile();
            refresh();
            return;
        }
        if (f.isDirectory()) {
            current = f;
            refresh();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(new String[]{"Export to /mnt/shared", "Delete"}, (d, which) -> {
                    if (which == 0) exportFile(f);
                    else confirmDelete(f);
                })
                .show();
    }

    private boolean onLongTap(int pos) {
        File f = rows.get(pos);
        if (f != null) confirmDelete(f);
        return true;
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete " + containerPath(f)
                        + (f.isDirectory() ? "(including all contents)" : "") + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    BootstrapInstaller.deleteRecursively(f);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** 导出：复制到 /sdcard/dsh-shared（容器内即 /mnt/shared）。 */
    private void exportFile(File f) {
        File destDir = ProotRunner.sharedDir();
        File dest = uniqueName(destDir, f.getName());
        try {
            copyFile(f, dest);
            Toast.makeText(this, "Exported to " + dest.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage()
                    + "\nEnable “All files access” in Settings and try again.", Toast.LENGTH_LONG).show();
        }
    }

    private static File uniqueName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + "(" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = Files.newInputStream(src.toPath());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 导入：SAF 选文件（免存储权限），复制进当前目录。 */
    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int ok = 0;
        int fail = 0;
        for (Uri u : uris) {
            try {
                importUri(u);
                ok++;
            } catch (IOException e) {
                fail++;
            }
        }
        refresh();
        Toast.makeText(this, "Import complete: " + ok + " item(s)" + (fail > 0 ? ", failed: " + fail + " item(s)" : ""),
                Toast.LENGTH_SHORT).show();
    }

    private void importUri(Uri uri) throws IOException {
        String name = displayName(uri);
        File dest = uniqueName(current, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Could not read the selected file.");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "import-" + System.currentTimeMillis();
        return name.replace('/', '_');
    }

    private void mkdirDialog() {
        EditText et = new EditText(this);
        et.setHint("Folder name");
        et.setSingleLine(true);
        int p = Ui.dp(this, 16);
        et.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim().replace("/", "");
                    if (name.isEmpty()) return;
                    File f = new File(current, name);
                    if (f.exists() || !f.mkdirs()) {
                        Toast.makeText(this, "Could not create the folder (it may already exist or permission may be missing).", Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
