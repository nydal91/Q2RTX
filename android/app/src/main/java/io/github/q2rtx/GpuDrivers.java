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
        String library = d.library;
        try {
            library = uniqueSonameLibrary(d);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't give " + d.library + " a unique soname", e);
        }
        args.addAll(Arrays.asList(
            "+set", "vk_hook_dir", ctx.getApplicationInfo().nativeLibraryDir,
            "+set", "vk_driver_dir", d.dir.getAbsolutePath() + "/",
            "+set", "vk_driver_name", library));
        return args;
    }

    /*
     * The system driver (vulkan.adreno.so) is usually already loaded in our process,
     * because Android draws the app's UI with Vulkan. A custom driver with the same
     * soname is then silently resolved to that already-loaded system driver. Qualcomm
     * driver packages often keep the original name, so give them a private copy whose
     * file name and DT_SONAME are unique (same length, so the ELF layout is unchanged).
     */
    private static final String SYSTEM_DRIVER_SONAME = "vulkan.adreno.so";
    private static final String UNIQUE_SONAME = "vulkan.q2rtx1.so"; // same length as above

    static String uniqueSonameLibrary(Driver d) throws IOException {
        File src = new File(d.dir, d.library);
        File patched = new File(d.dir, UNIQUE_SONAME);
        if (patched.isFile() && patched.lastModified() >= src.lastModified())
            return UNIQUE_SONAME;

        byte[] elf = readAll(src);
        int sonameOffset = findSoname(elf);
        if (sonameOffset < 0)
            return d.library;
        String soname = cString(elf, sonameOffset);
        if (!soname.equals(SYSTEM_DRIVER_SONAME))
            return d.library;

        byte[] name = UNIQUE_SONAME.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, elf, sonameOffset, name.length);

        File tmp = new File(d.dir, UNIQUE_SONAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(elf);
        }
        patched.setWritable(true);
        patched.delete();
        if (!tmp.renameTo(patched))
            throw new IOException("Couldn't write " + patched);
        patched.setReadOnly();
        Log.i(TAG, "Gave " + d.library + " the unique soname " + UNIQUE_SONAME);
        return UNIQUE_SONAME;
    }

    /** File offset of the DT_SONAME string of a 64-bit little-endian ELF, or -1. */
    private static int findSoname(byte[] e) {
        if (e.length < 64 || e[0] != 0x7f || e[1] != 'E' || e[2] != 'L' || e[3] != 'F' || e[4] != 2 || e[5] != 1)
            return -1;
        long phoff = le64(e, 0x20);
        int phentsize = le16(e, 0x36), phnum = le16(e, 0x38);

        long dynOff = -1, dynSize = 0;
        for (int i = 0; i < phnum; i++) {
            int ph = (int) (phoff + (long) i * phentsize);
            if (le32(e, ph) == 2 /* PT_DYNAMIC */) {
                dynOff = le64(e, ph + 0x08);
                dynSize = le64(e, ph + 0x20);
            }
        }
        if (dynOff < 0)
            return -1;

        long strtabVaddr = -1, sonameIndex = -1;
        for (long p = dynOff; p + 16 <= dynOff + dynSize; p += 16) {
            long tag = le64(e, (int) p), val = le64(e, (int) p + 8);
            if (tag == 0) break;
            if (tag == 5 /* DT_STRTAB */) strtabVaddr = val;
            if (tag == 14 /* DT_SONAME */) sonameIndex = val;
        }
        if (strtabVaddr < 0 || sonameIndex < 0)
            return -1;

        // translate the string table's virtual address through the PT_LOAD segments
        for (int i = 0; i < phnum; i++) {
            int ph = (int) (phoff + (long) i * phentsize);
            if (le32(e, ph) != 1 /* PT_LOAD */)
                continue;
            long off = le64(e, ph + 0x08), vaddr = le64(e, ph + 0x10), filesz = le64(e, ph + 0x20);
            if (strtabVaddr >= vaddr && strtabVaddr < vaddr + filesz) {
                long pos = strtabVaddr - vaddr + off + sonameIndex;
                return pos < e.length ? (int) pos : -1;
            }
        }
        return -1;
    }

    private static String cString(byte[] e, int off) {
        int end = off;
        while (end < e.length && e[end] != 0)
            end++;
        return new String(e, off, end - off, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] e, int o) { return (e[o] & 0xff) | (e[o + 1] & 0xff) << 8; }
    private static long le32(byte[] e, int o) { return (le16(e, o) | (long) le16(e, o + 2) << 16) & 0xffffffffL; }
    private static long le64(byte[] e, int o) { return le32(e, o) | le32(e, o + 4) << 32; }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0)
                off += n;
            return data;
        }
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
                    if (f.getName().endsWith(".so") && !f.getName().equals(UNIQUE_SONAME))
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
