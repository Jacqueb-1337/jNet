package net.jnet.host;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;

/** In-app updates: system-managed download, verified release digest, Android installer. */
final class InAppUpdater {
    private static final String RELEASES_URL =
        "https://api.github.com/repos/Jacqueb-1337/jNet/releases?per_page=20";
    private static final String PREFS = "app_update";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final MainActivity activity;
    private final DownloadManager downloads;
    private final SharedPreferences saved;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollDownload;

    private AlertDialog offerDialog;
    private AlertDialog progressDialog;
    private AlertDialog readyDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private UpdateRelease available;
    private boolean foreground;
    private boolean offered;
    private boolean hideProgress;
    private boolean verifying;
    private boolean readyDismissed;
    private boolean waitingForInstallPermission;

    InAppUpdater(MainActivity activity) {
        this.activity = activity;
        downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        saved = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void onCreate(boolean freshLaunch) {
        // Downloads survive rotation and process death via Android DownloadManager.
        if (freshLaunch && !hasDownload()) checkForUpdate();
    }

    void onResume() {
        foreground = true;
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) {
                openInstaller();
                return;
            }
        }
        if (hasDownload()) {
            if (downloadIsAlreadyInstalled()) {
                clearDownload();
                checkForUpdate();
            } else if (saved.getBoolean("verified", false)) {
                if (!readyDismissed) showReady();
            } else {
                if (!hideProgress) showProgress();
                startPolling();
            }
        } else if (available != null && !offered) {
            showOffer();
        }
    }

    void onPause() {
        foreground = false;
        handler.removeCallbacks(poll);
        dismissDialogs();
    }

    private void checkForUpdate() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String installed = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
                connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
                connection.setConnectTimeout(6500);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                byte[] body;
                try (var input = connection.getInputStream()) {
                    body = input.readNBytes(1024 * 1024 + 1);
                }
                if (body.length > 1024 * 1024) return;
                UpdateRelease release = UpdateRelease.newest(
                    new JSONArray(new String(body, StandardCharsets.UTF_8)), installed);
                if (release == null) return;
                handler.post(() -> {
                    if (activity.isDestroyed() || activity.isFinishing() || hasDownload()) return;
                    available = release;
                    if (foreground && !offered) showOffer();
                });
            } catch (Exception error) {
                android.util.Log.d("jNet", "Update check unavailable", error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "jNet update check").start();
    }

    private void showOffer() {
        if (!foreground || available == null || offerDialog != null) return;
        UpdateRelease release = available;
        offered = true;
        offerDialog = new AlertDialog.Builder(activity)
            .setTitle("jNet " + release.version + " is available")
            .setMessage("Download and install the update?")
            .setNegativeButton("Later", (dialog, which) -> {})
            .setPositiveButton("Download", (dialog, which) -> startDownload(release))
            .create();
        offerDialog.setOnDismissListener(dialog -> offerDialog = null);
        offerDialog.show();
    }

    private void startDownload(UpdateRelease release) {
        try {
            File output = updateFile(release.version);
            if (output.exists() && !output.delete()) {
                showError("Couldn't replace the previous download.", release);
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url))
                .setTitle("jNet " + release.version)
                .setDescription("Downloading update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    output.getName());
            long id = downloads.enqueue(request);
            // Save metadata before leaving the screen so download progress can be restored.
            saved.edit()
                .putLong("download_id", id)
                .putString("version", release.version)
                .putString("sha256", release.sha256)
                .putLong("size", release.size)
                .putBoolean("verified", false)
                .apply();
            available = null;
            hideProgress = false;
            readyDismissed = false;
            showProgress();
            startPolling();
        } catch (Exception e) {
            android.util.Log.e("jNet", "Cannot start update download", e);
            showError("Couldn't start the download.", release);
        }
    }

    private void showProgress() {
        if (!foreground || progressDialog != null || !hasDownload()) return;
        String version = saved.getString("version", "");
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(8), dp(24), dp(8));

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setIndeterminate(false);
        column.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(12)));

        progressText = new TextView(activity);
        progressText.setText("Connecting…");
        progressText.setTextSize(14);
        progressText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = dp(12);
        column.addView(progressText, labelParams);

        progressDialog = new AlertDialog.Builder(activity)
            .setTitle("Downloading jNet " + version)
            .setView(column)
            .setNegativeButton("Hide", (dialog, which) -> hideProgress = true)
            .setPositiveButton("Cancel download", (dialog, which) -> cancelDownload())
            .setCancelable(false)
            .create();
        progressDialog.setOnDismissListener(dialog -> {
            progressDialog = null;
            progressBar = null;
            progressText = null;
        });
        progressDialog.show();
    }

    private void startPolling() {
        handler.removeCallbacks(poll);
        if (foreground && hasDownload() && !saved.getBoolean("verified", false))
            handler.post(poll);
    }

    private void pollDownload() {
        if (!foreground || !hasDownload() || saved.getBoolean("verified", false)) return;
        long id = saved.getLong("download_id", -1);
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                failDownload("Download not found.");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long read = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long expected = cursor.getLong(cursor.getColumnIndexOrThrow(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (expected <= 0) expected = saved.getLong("size", 0);

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                verifyDownload();
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                failDownload("Download failed.");
                return;
            }
            if (progressText != null) {
                if (status == DownloadManager.STATUS_PAUSED) {
                    progressText.setText("Waiting for connection…");
                } else if (expected > 0) {
                    int percent = (int) Math.min(100, read * 100 / expected);
                    progressText.setText(String.format(Locale.getDefault(), "%d%%  ·  %s / %s",
                        percent, displayBytes(read), displayBytes(expected)));
                } else {
                    progressText.setText(displayBytes(read) + " downloaded");
                }
            }
            if (progressBar != null) {
                progressBar.setIndeterminate(expected <= 0);
                if (expected > 0)
                    progressBar.setProgress((int) Math.min(1000, read * 1000 / expected));
            }
        } catch (RuntimeException e) {
            android.util.Log.e("jNet", "Cannot read download progress", e);
            failDownload("Couldn't check download progress.");
            return;
        }
        handler.postDelayed(poll, 750);
    }

    private void verifyDownload() {
        if (verifying) return;
        verifying = true;
        if (progressText != null) progressText.setText("Checking download…");
        if (progressBar != null) progressBar.setIndeterminate(true);
        String version = saved.getString("version", "");
        String digest = saved.getString("sha256", "");
        long size = saved.getLong("size", 0);
        new Thread(() -> {
            boolean valid = false;
            try {
                File file = updateFile(version);
                if (size > 0 && file.length() == size && UpdateRelease.matchesSha256(file, digest)) {
                    PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(
                        file.getAbsolutePath(), 0);
                    valid = info != null && activity.getPackageName().equals(info.packageName)
                        && version.equals(info.versionName);
                }
            } catch (Exception e) {
                android.util.Log.w("jNet", "Update verification failed", e);
            }
            boolean approved = valid;
            handler.post(() -> {
                verifying = false;
                if (!hasDownload() || !version.equals(saved.getString("version", ""))) return;
                if (!approved) {
                    failDownload("Couldn't verify the downloaded update.");
                    return;
                }
                saved.edit().putBoolean("verified", true).apply();
                if (progressDialog != null) progressDialog.dismiss();
                if (foreground) showReady();
            });
        }, "jNet verify update").start();
    }

    private void showReady() {
        if (!foreground || readyDialog != null || !hasDownload()) return;
        UpdateRelease release = pendingRelease();
        if (release == null) return;
        String message = HostService.active
            ? "Ready to install. Sharing will stop while jNet updates."
            : "Ready to install.";
        readyDialog = new AlertDialog.Builder(activity)
            .setTitle("jNet " + release.version + " is ready")
            .setMessage(message)
            .setNegativeButton("Later", (dialog, which) -> readyDismissed = true)
            .setPositiveButton("Install", (dialog, which) -> install())
            .create();
        readyDialog.setOnDismissListener(dialog -> readyDialog = null);
        readyDialog.show();
    }

    private void install() {
        if (!saved.getBoolean("verified", false)) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                .setTitle("Allow installs from jNet")
                .setMessage("Android needs your permission to install this update.")
                .setNegativeButton("Not now", (dialog, which) -> {})
                .setPositiveButton("Continue", (dialog, which) -> {
                    waitingForInstallPermission = true;
                    try {
                        activity.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        waitingForInstallPermission = false;
                        Toast.makeText(activity, "Open Android settings to allow installs from jNet.",
                            Toast.LENGTH_LONG).show();
                    }
                }).show();
            return;
        }
        openInstaller();
    }

    private void openInstaller() {
        if (!saved.getBoolean("verified", false)) return;
        try {
            File file = updateFile(saved.getString("version", ""));
            if (!file.isFile()) {
                failDownload("Update file is missing.");
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.e("jNet", "Unable to open Android installer", e);
            new AlertDialog.Builder(activity)
                .setTitle("Couldn't open installer")
                .setMessage("Please check that your device allows app installs from jNet.")
                .setPositiveButton("OK", null)
                .show();
        }
    }

    private void cancelDownload() {
        clearDownload();
        Toast.makeText(activity, "Download cancelled", Toast.LENGTH_SHORT).show();
    }

    private void failDownload(String message) {
        UpdateRelease retry = pendingRelease();
        clearDownload();
        if (!foreground) return;
        showError(message, retry);
    }

    private void showError(String message, UpdateRelease retry) {
        if (!foreground) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("Couldn't update jNet")
            .setMessage(message)
            .setNegativeButton("Close", null);
        if (retry != null) builder.setPositiveButton("Retry", (dialog, which) -> startDownload(retry));
        builder.show();
    }

    private boolean hasDownload() {
        return saved.getLong("download_id", -1) >= 0 &&
            pendingRelease() != null;
    }

    private UpdateRelease pendingRelease() {
        String version = saved.getString("version", "");
        String digest = saved.getString("sha256", "");
        long size = saved.getLong("size", -1);
        if (version == null || !version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}") ||
                digest == null || !digest.matches("[0-9a-f]{64}") ||
                size <= 0 || size > 200L * 1024 * 1024) return null;
        return new UpdateRelease(version,
            "https://github.com/Jacqueb-1337/jNet/releases/download/v" + version +
                "/jNet-" + version + ".apk", digest, size);
    }

    private boolean downloadIsAlreadyInstalled() {
        try {
            String current = activity.getPackageManager().getPackageInfo(
                activity.getPackageName(), 0).versionName;
            return UpdateRelease.compare(current, saved.getString("version", "")) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearDownload() {
        handler.removeCallbacks(poll);
        long id = saved.getLong("download_id", -1);
        if (id >= 0) downloads.remove(id);
        String version = saved.getString("version", "");
        if (version != null && version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
            try {
                File file = updateFile(version);
                if (file.exists() && !file.delete())
                    android.util.Log.w("jNet", "Could not remove old update download");
            } catch (IOException ignored) {}
        }
        saved.edit().clear().apply();
        if (progressDialog != null) progressDialog.dismiss();
        if (readyDialog != null) readyDialog.dismiss();
        hideProgress = false;
        verifying = false;
    }

    private File updateFile(String version) throws IOException {
        if (!version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))
            throw new IOException("Invalid update filename");
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || (!dir.exists() && !dir.mkdirs()))
            throw new IOException("Update storage unavailable");
        return new File(dir, "jNet-" + version + ".apk");
    }

    private int dp(int amount) {
        return Math.round(amount * activity.getResources().getDisplayMetrics().density);
    }

    private static String displayBytes(long bytes) {
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(),
            "%.0f KB", Math.max(0, bytes) / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
    }

    private void dismissDialogs() {
        if (offerDialog != null) offerDialog.dismiss();
        if (progressDialog != null) progressDialog.dismiss();
        if (readyDialog != null) readyDialog.dismiss();
    }
}
