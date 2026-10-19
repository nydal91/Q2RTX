package io.github.q2rtx;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the game data lives and what is (still) missing.
 *
 * The engine uses SDL_AndroidGetExternalStoragePath() as its base directory,
 * which is Context.getExternalFilesDir(null): /storage/emulated/0/Android/data/&lt;package&gt;/files.
 * Game files go into its "baseq2" subfolder, exactly like on the desktop.
 */
final class GameData {
    private static final String TAG = "Q2RTX";

    /** Files shipped inside the APK (assets/) and copied to baseq2 on first start / update. */
    static final String[] BUNDLED = { "shaders.pkz" };

    private GameData() {}

    static File baseDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        return ext != null ? ext : ctx.getFilesDir();
    }

    static File baseq2(Context ctx) {
        return new File(baseDir(ctx), "baseq2");
    }

    /** Human-readable list of required files that are not there yet. Empty when ready to play. */
    static List<String> missingFiles(Context ctx) {
        File dir = baseq2(ctx);
        List<String> missing = new ArrayList<>();
        // pak0.pak: retail game or the shareware demo; holds the original maps and models
        if (!new File(dir, "pak0.pak").isFile())
            missing.add("pak0.pak (Quake II game data, full game or demo)");
        // NVIDIA's RTX content: materials, textures, environment maps
        if (!new File(dir, "q2rtx_media.pkz").isFile())
            missing.add("q2rtx_media.pkz (from a Quake II RTX install)");
        if (!new File(dir, "blue_noise.pkz").isFile())
            missing.add("blue_noise.pkz (from a Quake II RTX install)");
        if (!new File(dir, "shaders.pkz").isFile())
            missing.add("shaders.pkz (bundled - reinstall the app)");
        return missing;
    }

    /** Copies the APK's bundled files into baseq2 once per install/update of the APK. */
    static void extractBundled(Context ctx) {
        File dir = baseq2(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "Couldn't create " + dir);
            return;
        }

        long version = appVersion(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences("q2rtx", Context.MODE_PRIVATE);
        boolean upToDate = prefs.getLong("bundled_version", -1) == version;

        for (String name : BUNDLED) {
            File out = new File(dir, name);
            if (upToDate && out.isFile())
                continue;
            File tmp = new File(dir, name + ".tmp");
            try (InputStream in = ctx.getAssets().open(name);
                 OutputStream os = new FileOutputStream(tmp)) {
                copy(in, os, null);
            } catch (IOException e) {
                Log.e(TAG, "Couldn't extract " + name, e);
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(out))
                Log.e(TAG, "Couldn't move " + tmp + " to " + out);
        }
        prefs.edit().putLong("bundled_version", version).apply();
    }

    interface Progress {
        void add(long bytes);
    }

    static void copy(InputStream in, OutputStream out, Progress progress) throws IOException {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (progress != null)
                progress.add(n);
        }
    }

    private static long appVersion(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            // changes with every (re)install, so development builds refresh the shaders too
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}
