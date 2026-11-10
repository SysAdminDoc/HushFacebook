package app.hushfacebook.unpacker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hushfacebook.superpack.DecodeStore;
import hushfacebook.superpack.StorelessApk;

/**
 * One screen: pick a Facebook Android 9+ build, unpack it on a worker thread, then share the copy
 * to Morphe Manager or save it.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "HushUnpacker";
    private static final String MANAGER = "app.morphe.manager";
    private static final int PICK = 1;
    private static final int SAVE = 2;
    private static final String KEPT_NAME = "unpackedName";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Button action;
    private Button send;
    private ProgressBar progress;
    private TextView status;
    private TextView log;
    private ScrollView logScroll;
    private boolean busy;
    /**
     * The finished copy, kept until it's saved or the app starts afresh. Android may stop the app
     * while Morphe Manager or the save screen is open; the copy and its name come back with the screen.
     */
    private File unpacked;
    private String unpackedName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLog();
        setContentView(R.layout.activity_main);
        action = findViewById(R.id.action);
        send = findViewById(R.id.send);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        if (Build.VERSION.SDK_INT >= 35) fitSystemBars(findViewById(R.id.root));
        action.setOnClickListener(v -> {
            if (unpacked != null) askWhereToSave();
            else pick();
        });
        send.setOnClickListener(v -> sendToManager());
        setStatus(getString(R.string.status_idle, abi()), R.color.text);

        File marker = marker();
        if (marker.exists()) {
            marker.delete();
            fail(getString(R.string.stopped_in_library, abi()));
        }
        String keptName = savedInstanceState == null ? null : savedInstanceState.getString(KEPT_NAME);
        File kept = keptName == null ? null : UnpackedProvider.finishedCopy(this);
        if (kept != null) {
            unpacked = kept;
            unpackedName = keptName;
            showReady();
        } else if (savedInstanceState == null) {
            deleteTree(workDir());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (unpackedName != null) state.putString(KEPT_NAME, unpackedName);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException none) {
            fail(getString(R.string.no_picker));
        }
    }

    private void askWhereToSave() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType(unpackedName))
                .putExtra(Intent.EXTRA_TITLE, unpackedName);
        try {
            startActivityForResult(intent, SAVE);
        } catch (ActivityNotFoundException none) {
            fail(getString(R.string.no_picker));
        }
    }

    /**
     * Shares the copy with Morphe Manager, which takes APKs and bundles shared to it straight into
     * patching. The grant covers this one file and lasts as long as Manager's screen for it does.
     */
    private void sendToManager() {
        Uri uri = UnpackedProvider.uriFor(unpackedName);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setPackage(MANAGER)
                .setType(mimeType(unpackedName))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(unpackedName, uri));
        try {
            startActivity(intent);
            appendLog("sent " + unpackedName + " to Morphe Manager");
            setStatus(getString(R.string.status_sent), R.color.good);
        } catch (ActivityNotFoundException none) {
            fail(getString(R.string.no_manager));
        }
    }

    static String mimeType(String name) {
        return name.endsWith(".apk") ? "application/vnd.android.package-archive" : "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null && !busy) {
            unpack(uri);
        } else if (requestCode == SAVE) {
            if (resultCode == RESULT_OK && uri != null && unpacked != null) save(uri);
            else setStatus(getString(R.string.status_not_saved), R.color.text);
        }
    }

    private void unpack(Uri uri) {
        String name = displayName(uri);
        log.setText("");
        setBusy(true);
        setStatus(getString(R.string.status_reading, name), R.color.text);
        appendLog(name);
        worker.execute(() -> {
            File work = workDir();
            deleteTree(work);
            try {
                if (!work.mkdirs()) throw new IOException("can't make " + work);
                String extension = name.toLowerCase(Locale.ROOT).endsWith(".apk") ? ".apk" : ".apkm";
                File input = new File(work, "input" + extension);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(input)) {
                    if (in == null) throw new IOException("the picker gave no file");
                    Unpacker.copy(in, out);
                }
                appendLogLater(Unpacker.megabytes(input) + " MB read");
                // The bundle, its base.apk, the store, the decoded DEX files and the copy, at most.
                long needed = input.length() * 4 + (64L << 20);
                long free = work.getUsableSpace();
                if (free < needed) throw new Unpacker.Refusal(R.string.no_space, needed >> 20, free >> 20);
                File output = new File(work, "unpacked" + extension);
                new Unpacker(work, new File(getFilesDir(), "superpack"), marker(), abi(), this::appendLogLater,
                        stage -> runOnUiThread(() -> setStatus(getString(stage), R.color.text)))
                        .unpack(input, output);
                File[] leftovers = work.listFiles();
                if (leftovers != null) for (File file : leftovers) if (!file.equals(output)) deleteTree(file);
                String saveAs = stem(name) + "-unpacked" + extension;
                runOnUiThread(() -> {
                    unpacked = output;
                    unpackedName = saveAs;
                    setBusy(false);
                    showReady();
                });
            } catch (Throwable failure) {
                deleteTree(work);
                String message = reason(failure);
                if (!(failure instanceof Unpacker.Refusal) && !(failure instanceof DecodeStore.Refusal)
                        && !(failure instanceof StorelessApk.Refusal)) {
                    writeCrashLog(failure);
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    fail(message);
                });
            }
        });
    }

    private void showReady() {
        action.setText(R.string.save);
        send.setVisibility(View.VISIBLE);
        setStatus(getString(R.string.status_ready, unpackedName), R.color.good);
    }

    private void save(Uri uri) {
        File source = unpacked;
        String name = unpackedName;
        setBusy(true);
        setStatus(getString(R.string.status_saving, name), R.color.text);
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("the picker gave no place to write");
                Unpacker.copy(in, out);
                deleteTree(workDir());
                runOnUiThread(() -> {
                    unpacked = null;
                    unpackedName = null;
                    setBusy(false);
                    action.setText(R.string.pick);
                    send.setVisibility(View.GONE);
                    setStatus(getString(R.string.status_saved, name), R.color.good);
                    appendLog("saved " + name);
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable failure) {
                writeCrashLog(failure);
                String message = reason(failure);
                runOnUiThread(() -> {
                    setBusy(false);
                    fail(message);
                });
            }
        });
    }

    private String reason(Throwable failure) {
        if (failure instanceof Unpacker.Refusal) {
            Unpacker.Refusal refusal = (Unpacker.Refusal) failure;
            return getString(refusal.message, refusal.arguments);
        }
        if (failure instanceof DecodeStore.Refusal || failure instanceof StorelessApk.Refusal) return failure.getMessage();
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private void fail(String message) {
        setStatus(getString(R.string.status_failed, message), R.color.bad);
        appendLog("FAILED " + message);
        Log.w(TAG, message);
        // The whole reason is in the dialog and the log; a toast holds two short lines at most.
        Toast.makeText(this, R.string.failed_title, Toast.LENGTH_LONG).show();
        new AlertDialog.Builder(this)
                .setTitle(R.string.failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setBusy(boolean now) {
        busy = now;
        action.setEnabled(!now);
        send.setEnabled(!now);
        progress.setVisibility(now ? View.VISIBLE : View.INVISIBLE);
        if (now) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(getColor(color));
    }

    private void appendLogLater(String line) {
        runOnUiThread(() -> appendLog(line));
    }

    private void appendLog(String line) {
        Log.i(TAG, line);
        log.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException unreadable) {
            Log.w(TAG, "no display name for " + uri, unreadable);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "facebook.apkm" : last.substring(last.lastIndexOf('/') + 1);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** The CPU this process runs as, which is the one the library has to be built for. */
    private static String abi() {
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        return abis.length > 0 ? abis[0] : Build.SUPPORTED_ABIS[0];
    }

    private File workDir() {
        return new File(getCacheDir(), "unpack");
    }

    private File marker() {
        return new File(getFilesDir(), "unpacking");
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) Log.w(TAG, "couldn't delete " + file);
    }

    /** Android 15 draws apps under the system bars, so the content takes their size as padding. */
    private static void fitSystemBars(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    private void installCrashLog() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            writeCrashLog(failure);
            if (previous != null) previous.uncaughtException(thread, failure);
        });
    }

    /** Appends to crash.log in the app's external files folder (Android/data/app.hushfacebook.unpacker/files). */
    private void writeCrashLog(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        Log.e(TAG, "failure", failure);
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + abi() + "\n" + trace + "\n";
        try {
            Files.write(new File(dir, "crash.log").toPath(), entry.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            Log.e(TAG, "couldn't write crash.log", unwritable);
        }
    }
}
