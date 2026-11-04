package com.dshmobile.app;

import android.content.Context;

import java.io.File;
import java.util.Arrays;

/**
 * node-pty 原生模块（pty.node）自检与修复。
 *
 * npm 安装 dsh 时 node-pty 需 node-gyp 现场编译，编译失败（头文件下载被墙、
 * npm 以 root 运行降权等）会被当 optional 依赖静默跳过、安装"Success"，
 * 但 dsh web 启动即崩（subprocess 插件加载 pty.node 失败）。
 * 安装流程与前台服务启动前都调用本类兜底。
 */
public final class NodePtyFixer {

    private static final String PTY_DIR =
            "opt/node/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty";
    private static final String NODE_GYP =
            "/opt/node/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js";

    private NodePtyFixer() {
    }

    /** dsh 已安装但 pty.node 缺失（node-pty 加载时会检查的三个位置都没有）时返回 true。 */
    public static boolean needsFix(File rootfs) {
        File dir = new File(rootfs, PTY_DIR);
        if (!dir.isDirectory()) {
            return false;
        }
        return !new File(dir, "build/Release/pty.node").isFile()
                && !new File(dir, "build/Debug/pty.node").isFile()
                && !new File(dir, "prebuilds/linux-arm64/pty.node").isFile();
    }

    /**
     * 容器内用 node-gyp 重建 pty.node。
     * 先试本地头文件（--nodedir=/opt/node，完全离线）；失败再回退到从 Node
     * 下载镜像取头文件（--dist-url，与 Node 本体同一个源，安装时已验证可达）。
     * 工具链（python3/make/g++）由安装流程先行装好。
     *
     * @param logFile 追加写 node-gyp 输出的日志文件
     * @return 修复成功（pty.node 已生成）返回 true
     */
    public static boolean fix(Context ctx, File logFile) {
        File rootfs = ProotRunner.rootfsDir(ctx);
        attempt(ctx, "--nodedir=/opt/node", logFile);
        if (!needsFix(rootfs)) {
            return true;
        }
        attempt(ctx, "--dist-url=" + Prefs.of(ctx).getNodeMirror(), logFile);
        return !needsFix(rootfs);
    }

    private static void attempt(Context ctx, String gypArgs, File logFile) {
        try {
            Process p = ProotRunner.exec(ctx, Arrays.asList("/bin/bash", "-c",
                    "cd /" + PTY_DIR + " && exec /opt/node/bin/node "
                            + NODE_GYP + " rebuild " + gypArgs), logFile);
            p.waitFor();
        } catch (Exception ignored) {
            // 失败由调用方通过 needsFix 复判
        }
    }
}
