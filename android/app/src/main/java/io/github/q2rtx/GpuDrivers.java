package io.github.q2rtx;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Custom Vulkan drivers (e.g. Mesa Turnip for Adreno), loaded by the engine through
 * libadrenotools instead of the system driver.
 *
 * Drivers are installed from the zip packages emulators use (a driver .so plus an
 * optional meta.json naming it) into the app's private files directory: the dynamic
 * linker refuses to load native code from shared storage.
 */
final class GpuDrivers {
    private static final String TAG = "Q2RTX";
    private static final String PREF_SELECTED = "gpu_driver";

    static final class Driver {
        final File dir;
        final String library;
        final String name;
        final String version;

        Driver(File dir, String library, String name, String version) {
            this.dir = dir;
            this.library = library;
            this.name = name;
            this.version = version;
        }

        String label() {
            return version.isEmpty() ? name : name + " (" + version + ")";
        }
    }

    private GpuDrivers() {}

    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "gpu_drivers");
    }

    static List<Driver> installed(Context ctx) {
        List<Driver> drivers = new ArrayList<>();
        File[] dirs = root(ctx).listFiles();
        if (dirs == null)
            return drivers;
        Arrays.sort(dirs);
        for (File dir : dirs) {
            Driver d = read(dir);
            if (d != null)
                drivers.add(d);
        }
        return drivers;
    }

    /** The driver the player picked, or null for the system driver. */
    static Driver selected(Context ctx) {
        String id = prefs(ctx).getString(PREF_SELECTED, "");
        if (id.isEmpty())
            return null;
        return read(new File(root(ctx), id));
    }

    static void select(Context ctx, Driver driver) {
        prefs(ctx).edit().putString(PREF_SELECTED, driver == null ? "" : driver.dir.getName()).apply();
    }

    static void delete(Context ctx, Driver driver) {
        Driver current = selected(ctx);
        if (current != null && current.dir.equals(driver.dir))
            select(ctx, null);
        deleteRecursive(driver.dir);
    }

    /** Engine command line arguments that make it load the selected driver. */
    static List<String> engineArguments(Context ctx) {
        List<String> args = new ArrayList<>();
        Driver d = selected(ctx);
        if (d == null)
            return args;
        args.addAll(Arrays.asList(
            "+set", "vk_hook_dir", ctx.getApplicationInfo().nativeLibraryDir,
            "+set", "vk_driver_dir", d.dir.getAbsolutePath() + "/",
            "+set", "vk_driver_name", d.library));
        return args;
    }

    /** Installs a driver package (zip). Returns the installed driver. */
    static Driver install(Context ctx, Uri zip) throws IOException {
        File root = root(ctx);
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Couldn't create " + root);

        File tmp = new File(root, ".installing");
        deleteRecursive(tmp);
        if (!tmp.mkdirs())
            throw new IOException("Couldn't create " + tmp);

        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(zip)) {
                if (in == null)
                    throw new IOException("Couldn't open the file");
                ZipInputStream zin = new ZipInputStream(in);
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory())
                        continue;
                    // flatten: drivers are a single .so plus metadata
                    String name = new File(e.getName()).getName();
                    if (name.isEmpty() || name.startsWith("."))
                        continue;
                    try (OutputStream out = new FileOutputStream(new File(tmp, name))) {
                        int n;
                        while ((n = zin.read(buf)) > 0)
                            out.write(buf, 0, n);
                    }
                }
            }

            String library = null, name = null, version = "";
            File meta = new File(tmp, "meta.json");
            if (meta.isFile()) {
                JSONObject json = new JSONObject(readText(meta));
                library = json.optString("libraryName", null);
                name = json.optString("name", null);
                version = json.optString("driverVersion", json.optString("packageVersion", ""));
            }
            if (library == null || !new File(tmp, library).isFile()) {
                library = null;
                File[] files = tmp.listFiles();
                if (files != null)
                    for (File f : files)
                        if (f.getName().endsWith(".so")) {
                            library = f.getName();
                            break;
                        }
            }
            if (library == null)
                throw new IOException("No driver library (.so) found in the zip");
            if (name == null || name.isEmpty())
                name = library;

            String id = name.replaceAll("[^A-Za-z0-9._-]+", "_").toLowerCase(Locale.ROOT);
            File dest = new File(root, id);
            for (int i = 2; dest.exists(); i++)
                dest = new File(root, id + "_" + i);
            if (!tmp.renameTo(dest))
                throw new IOException("Couldn't move the driver into place");

            // Android 14+ refuses to load writable code files
            File[] files = dest.listFiles();
            if (files != null)
                for (File f : files)
                    f.setReadOnly();

            Driver d = read(dest);
            if (d == null)
                throw new IOException("The installed driver could not be read back");
            return d;
        } catch (org.json.JSONException ex) {
            throw new IOException("Invalid meta.json: " + ex.getMessage());
        } finally {
            deleteRecursive(tmp);
        }
    }

    private static Driver read(File dir) {
        if (!dir.isDirectory() || dir.getName().startsWith("."))
            return null;
        String library = null, name = dir.getName(), version = "";
        File meta = new File(dir, "meta.json");
        try {
            if (meta.isFile()) {
                JSONObject json = new JSONObject(readText(meta));
                library = json.optString("libraryName", null);
                name = json.optString("name", name);
                version = json.optString("driverVersion", json.optString("packageVersion", ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad meta.json in " + dir, e);
        }
        if (library == null || !new File(dir, library).isFile()) {
            library = null;
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files)
                    if (f.getName().endsWith(".so"))
                        library = f.getName();
        }
        return library == null ? null : new Driver(dir, library, name, version);
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0)
                off += n;
            return new String(data, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null)
            for (File c : children)
                deleteRecursive(c);
        f.setWritable(true);
        f.delete();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("q2rtx", Context.MODE_PRIVATE);
    }
}
