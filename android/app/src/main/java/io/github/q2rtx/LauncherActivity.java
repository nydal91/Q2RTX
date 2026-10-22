package io.github.q2rtx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First screen: shows whether the game data is in place, lets the player import it
 * from any folder (Storage Access Framework, so no storage permission is needed),
 * and starts the game.
 */
public class LauncherActivity extends Activity {
    private static final String TAG = "Q2RTX";
    private static final int REQUEST_IMPORT = 1;
    private static final int REQUEST_DRIVER = 2;
    private static final int GREEN = Color.rgb(118, 185, 0);

    private TextView status;
    private Button importButton, playButton, driverButton, testsButton;
    private volatile boolean importing;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GameData.extractBundled(this);
        setContentView(buildLayout());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!importing)
            refresh();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildLayout() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(32), dp(24), dp(32), dp(24));
        col.setBackgroundColor(Color.rgb(12, 12, 12));

        TextView title = new TextView(this);
        title.setText("QUAKE II RTX");
        title.setTextColor(GREEN);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        col.addView(title);

        TextView info = new TextView(this);
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setText(
            "Requires a GPU with Vulkan ray tracing (VK_KHR_ray_query): Snapdragon 8 Gen 2 or newer, "
            + "Exynos 2200/2400, Dimensity 9200 or newer.\n\n"
            + "Game data is not included. Copy the baseq2 folder of a Quake II RTX install "
            + "(Steam or the GitHub release; it contains q2rtx_media.pkz, blue_noise.pkz and pak0.pak) "
            + "to this device, then tap Import and pick that folder (or the folder containing it).\n\n"
            + "You can also copy the files over USB into:\n" + GameData.baseq2(this).getAbsolutePath());
        info.setPadding(0, dp(8), 0, dp(16));
        col.addView(info);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, 0, 0, dp(16));
        col.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);

        importButton = new Button(this);
        importButton.setText("Import game data…");
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });
        row.addView(importButton);

        playButton = new Button(this);
        playButton.setText("Play");
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        row.addView(playButton, lp);

        Button logButton = new Button(this);
        logButton.setText("Show log");
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(16);
        row.addView(logButton, lp2);

        col.addView(row);

        driverButton = new Button(this);
        driverButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDriverDialog(); }
        });
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = dp(12);
        col.addView(driverButton, lp3);

        testsButton = new Button(this);
        testsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTestsDialog(); }
        });
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp4.topMargin = dp(8);
        col.addView(testsButton, lp4);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(12, 12, 12));
        scroll.addView(col);
        return scroll;
    }

    private void refresh() {
        List<String> missing = GameData.missingFiles(this);
        if (missing.isEmpty()) {
            status.setText("✔ Game data found. Ready to play.");
            status.setTextColor(GREEN);
        } else {
            StringBuilder sb = new StringBuilder("Missing:\n");
            for (String m : missing)
                sb.append("  • ").append(m).append('\n');
            status.setText(sb.toString().trim());
            status.setTextColor(Color.rgb(255, 180, 60));
        }
        playButton.setEnabled(missing.isEmpty() && !importing);
        importButton.setEnabled(!importing);
        GpuDrivers.Driver driver = GpuDrivers.selected(this);
        driverButton.setText("GPU driver: " + (driver == null ? "System" : driver.label()));
        driverButton.setEnabled(!importing);
        testsButton.setText("Graphics tests: " + (GraphicsTests.anyActive(this) ? "ON" : "off"));
    }

    private void play() {
        startActivity(new Intent(this, Q2RTXActivity.class));
    }

    // --------------------------------------------------------- GPU drivers

    /*
     * One flat list, applied on tap: in landscape on a phone there is no room for a
     * radio list plus explanation plus buttons (the buttons ended up off-screen).
     */
    private void showDriverDialog() {
        final List<GpuDrivers.Driver> drivers = GpuDrivers.installed(this);
        GpuDrivers.Driver current = GpuDrivers.selected(this);

        final List<String> items = new ArrayList<>();
        items.add((current == null ? "✔ " : "    ") + "System driver (default)");
        for (GpuDrivers.Driver d : drivers) {
            boolean on = current != null && current.dir.equals(d.dir);
            items.add((on ? "✔ " : "    ") + d.label());
        }
        final int installIndex = items.size();
        items.add("＋ Install driver from .zip…");
        final int deleteIndex = drivers.isEmpty() ? -1 : items.size();
        if (deleteIndex >= 0)
            items.add("✖ Delete a driver…");
        final int helpIndex = items.size();
        items.add("ⓘ About custom drivers");

        new AlertDialog.Builder(this)
            .setTitle("GPU driver")
            .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        GpuDrivers.select(LauncherActivity.this, null);
                        refresh();
                    } else if (which <= drivers.size()) {
                        GpuDrivers.select(LauncherActivity.this, drivers.get(which - 1));
                        refresh();
                    } else if (which == installIndex) {
                        pickDriverZip();
                    } else if (which == deleteIndex) {
                        showDeleteDriverDialog(drivers);
                    } else if (which == helpIndex) {
                        new AlertDialog.Builder(LauncherActivity.this)
                            .setTitle("Custom GPU drivers")
                            .setMessage("Drivers such as Mesa Turnip are loaded with libadrenotools "
                                + "(Snapdragon / Adreno only), from the .zip packages made for emulators.\n\n"
                                + "Quake II RTX needs ray tracing (VK_KHR_ray_query). If a driver lacks it, or the "
                                + "game doesn't start, pick \"System driver\" again.\n\n"
                                + "\"Show log\" tells you which driver the game actually used.")
                            .setPositiveButton("OK", null)
                            .show();
                    }
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void showDeleteDriverDialog(final List<GpuDrivers.Driver> drivers) {
        String[] names = new String[drivers.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = drivers.get(i).label();
        new AlertDialog.Builder(this)
            .setTitle("Delete which driver?")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    GpuDrivers.delete(LauncherActivity.this, drivers.get(which));
                    refresh();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickDriverZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" });
        startActivityForResult(intent, REQUEST_DRIVER);
    }

    private void installDriver(final Uri zip) {
        status.setText("Installing GPU driver…");
        status.setTextColor(Color.WHITE);
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    GpuDrivers.Driver d = GpuDrivers.install(LauncherActivity.this, zip);
                    GpuDrivers.select(LauncherActivity.this, d);
                    msg = "Installed and selected GPU driver: " + d.label() + " (" + d.library + ")";
                } catch (IOException e) {
                    Log.e(TAG, "Driver install failed", e);
                    msg = "Couldn't install the driver: " + e.getMessage();
                }
                final String text = msg;
                ui.post(new Runnable() {
                    @Override public void run() {
                        refresh();
                        status.setText(text + "\n\n" + status.getText());
                    }
                });
            }
        }, "q2rtx-driver").start();
    }

    // ------------------------------------------------------ graphics tests

    /** Tap a row to change it; the list reopens so several can be set in a row. */
    private void showTestsDialog() {
        final String[] items = {
            "Shader build: " + GraphicsTests.SHADER_LABELS[GraphicsTests.shader(this)] + "  (tap to change)",
            (GraphicsTests.noDenoiser(this) ? "✔ " : "    ") + "Denoiser off (grainy image)",
            (GraphicsTests.simpleSky(this) ? "✔ " : "    ") + "Simple sky",
            (GraphicsTests.lightLoad(this) ? "✔ " : "    ") + "Light load (low resolution, no bounces)",
            "↺ Reset all to default",
        };
        new AlertDialog.Builder(this)
            .setTitle("Graphics tests")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    Context c = LauncherActivity.this;
                    switch (which) {
                    case 0: GraphicsTests.setShader(c, (GraphicsTests.shader(c) + 1) % GraphicsTests.SHADERS.length); break;
                    case 1: GraphicsTests.setNoDenoiser(c, !GraphicsTests.noDenoiser(c)); break;
                    case 2: GraphicsTests.setSimpleSky(c, !GraphicsTests.simpleSky(c)); break;
                    case 3: GraphicsTests.setLightLoad(c, !GraphicsTests.lightLoad(c)); break;
                    case 4: GraphicsTests.reset(c); break;
                    }
                    refresh();
                    showTestsDialog();
                }
            })
            .setNegativeButton("Done", null)
            .show();
    }

    // ----------------------------------------------------------------- log

    /**
     * The engine writes its console to baseq2/logs/console.log. Android/data is hard to
     * reach on modern Android, so show the interesting part here and offer to copy it.
     */
    private void showLog() {
        File log = new File(GameData.baseq2(this), "logs/console.log");
        String text;
        if (!log.isFile()) {
            text = "No log yet. Start the game once, then come back here.\n(" + log.getAbsolutePath() + ")";
        } else {
            text = summarizeLog(log);
        }

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        final String copy = text;
        new AlertDialog.Builder(this)
            .setTitle("Game log")
            .setView(scroll)
            .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null)
                        cm.setPrimaryClip(ClipData.newPlainText("Q2RTX log", copy));
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Device/driver lines and errors from the last run, plus its final lines. */
    private static String summarizeLog(File log) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = r.readLine()) != null) {
                // the log is appended to on every run; keep only the latest one
                if (line.contains("Engine version:"))
                    lines.clear();
                lines.add(line);
            }
        } catch (IOException e) {
            return "Couldn't read " + log + ": " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        String[] keys = { "Engine version", "GPU:", "Limits:", "Optional features", "FP16", "Picked physical",
            "Using VK_", "Vulkan:", "Vulkan driver", "Test settings", "custom Vulkan", "Linear filtering", "Recreated", "rror", "ailed", "ouldn't", "WARNING", "FATAL", "not support" };
        for (String l : lines) {
            for (String k : keys) {
                if (l.contains(k)) {
                    sb.append(l).append('\n');
                    break;
                }
            }
        }
        sb.append("\n--- last lines ---\n");
        for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++)
            sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------- import

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DRIVER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                installDriver(data.getData());
            return;
        }
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;

        final Uri tree = data.getData();
        importing = true;
        refresh();
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    result = importTree(tree);
                } catch (Exception e) {
                    Log.e(TAG, "Import failed", e);
                    result = "Import failed: " + e.getMessage();
                }
                final String msg = result;
                ui.post(new Runnable() {
                    @Override public void run() {
                        importing = false;
                        refresh();
                        if (msg != null)
                            status.setText(msg + "\n\n" + status.getText());
                    }
                });
            }
        }, "q2rtx-import").start();
    }

    private static final String[] CHILD_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    };

    private static final class Entry {
        String id, name;
        boolean dir;
        long size;
    }

    private List<Entry> children(Uri tree, String docId) {
        List<Entry> out = new java.util.ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(uri, CHILD_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getString(0);
                e.name = c.getString(1);
                e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                e.size = c.isNull(3) ? 0 : c.getLong(3);
                out.add(e);
            }
        }
        return out;
    }

    /** Files that are never imported: desktop binaries, and things the APK provides itself. */
    private static boolean skip(String name, boolean dir) {
        String n = name.toLowerCase(Locale.ROOT);
        if (dir)
            return n.equals("shader_vkpt") || n.startsWith(".");
        return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".exe") || n.endsWith(".dylib")
            || n.equals("shaders.pkz") || n.startsWith(".");
    }

    private long totalBytes, doneBytes, lastUpdate;
    private int fileCount;

    private String importTree(Uri tree) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);

        // Accept the Quake II folder itself or its baseq2 subfolder.
        String sourceId = rootId;
        for (Entry e : children(tree, rootId)) {
            if (e.dir && e.name.equalsIgnoreCase("baseq2")) {
                sourceId = e.id;
                break;
            }
        }

        totalBytes = measure(tree, sourceId);
        doneBytes = 0;
        fileCount = 0;
        if (totalBytes == 0)
            return "That folder has no files to import.";

        File dest = GameData.baseq2(this);
        copyDir(tree, sourceId, dest);
        return String.format(Locale.ROOT, "Imported %d files (%.0f MB).", fileCount, doneBytes / 1048576.0);
    }

    private long measure(Uri tree, String docId) {
        long sum = 0;
        for (Entry e : children(tree, docId)) {
            if (skip(e.name, e.dir))
                continue;
            sum += e.dir ? measure(tree, e.id) : e.size;
        }
        return sum;
    }

    private void copyDir(Uri tree, String docId, File dest) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs())
            throw new IOException("Couldn't create " + dest);

        for (Entry e : children(tree, docId)) {
            if (skip(e.name, e.dir))
                continue;
            File out = new File(dest, e.name);
            if (e.dir) {
                copyDir(tree, e.id, out);
                continue;
            }
            // resume-friendly: an identical-size file is assumed to be already imported
            if (out.isFile() && out.length() == e.size) {
                doneBytes += e.size;
                fileCount++;
                continue;
            }
            File tmp = new File(dest, e.name + ".part");
            Uri src = DocumentsContract.buildDocumentUriUsingTree(tree, e.id);
            try (InputStream in = getContentResolver().openInputStream(src);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null)
                    throw new IOException("Couldn't open " + e.name);
                final String name = e.name;
                GameData.copy(in, os, new GameData.Progress() {
                    @Override public void add(long bytes) {
                        doneBytes += bytes;
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 250) {
                            lastUpdate = now;
                            showProgress(name);
                        }
                    }
                });
            } catch (IOException ex) {
                tmp.delete();
                throw ex;
            }
            if (out.exists())
                out.delete();
            if (!tmp.renameTo(out))
                throw new IOException("Couldn't write " + out);
            fileCount++;
        }
    }

    private void showProgress(String name) {
        final String text = String.format(Locale.ROOT, "Importing %s…\n%.0f / %.0f MB",
            name, doneBytes / 1048576.0, totalBytes / 1048576.0);
        ui.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
                status.setTextColor(Color.WHITE);
            }
        });
    }
}
