package by.gpguide.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String RELEASE_API = "https://api.github.com/repos/zigorminsk-debug/gp/releases/latest";
    private static final String RELEASE_PACKAGE = "by.gpguide.android";
    private static final int REQUEST_INSTALL_PERMISSION = 7412;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private WebView webView;
    private String latestApkUrl;
    private String latestVersionName;
    private File downloadedApk;
    private boolean updateAvailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(14, 27, 48));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(14, 27, 48));
        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(8, 17, 31));
        if (Build.VERSION.SDK_INT >= 30) {
            webView.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.addJavascriptInterface(new UpdateBridge(), "AppUpdates");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Uri page = Uri.parse(url);
                if ("file".equals(page.getScheme()) && "/android_asset/index.html".equals(page.getPath())) checkForUpdates();
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean routeUrl(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null
                && uri.getPath().startsWith("/android_asset/")) return false;
        if ("https".equals(uri.getScheme())) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
            catch (Exception ignored) { }
        }
        return true;
    }

    private void checkForUpdates() {
        publishState("Проверяем последнюю версию…", false, "");
        background.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "GP-Guide-Android-Updater");
                int response = connection.getResponseCode();
                if (response == 404) {
                    publishState("Пока нет опубликованных релизов.", false, "");
                    return;
                }
                if (response < 200 || response >= 300) {
                    publishState("Не удалось проверить обновления (GitHub: " + response + ").", false, "");
                    return;
                }
                String json = new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8);
                JSONObject release = new JSONObject(json);
                String tag = release.optString("tag_name", "");
                long releaseCode = versionCodeFromTag(tag);
                if (releaseCode <= BuildConfig.VERSION_CODE) {
                    updateAvailable = false;
                    latestApkUrl = null;
                    publishState("Установлена актуальная версия " + BuildConfig.VERSION_NAME + ".", false, "");
                    return;
                }
                JSONArray assets = release.optJSONArray("assets");
                String assetUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        String url = asset.optString("browser_download_url", "");
                        if (name.endsWith(".apk") && url.startsWith("https://github.com/")) {
                            assetUrl = url;
                            break;
                        }
                    }
                }
                if (assetUrl == null) {
                    publishState("Релиз " + tag + " найден, но APK в нём отсутствует.", false, "");
                    return;
                }
                latestApkUrl = assetUrl;
                latestVersionName = tag;
                updateAvailable = true;
                publishState("Доступно обновление " + tag + ". Нажмите «Загрузить и установить».", true, tag);
            } catch (Exception e) {
                publishState("Проверка обновлений не удалась. Проверьте интернет и повторите позже.", false, "");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private long versionCodeFromTag(String tag) {
        try {
            String[] pieces = tag.replaceFirst("^[vV]", "").split("\\.");
            return Long.parseLong(pieces[pieces.length - 1]);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private void downloadUpdate() {
        if (!updateAvailable || latestApkUrl == null) {
            checkForUpdates();
            return;
        }
        final String url = latestApkUrl;
        final String version = latestVersionName;
        publishState("Загружаем обновление " + version + " в приложение…", false, version);
        background.execute(() -> {
            HttpURLConnection connection = null;
            try {
                File folder = new File(getFilesDir(), "updates");
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create update folder");
                File target = new File(folder, "gp-guide-update.apk");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "GP-Guide-Android-Updater");
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) throw new IllegalStateException("Download HTTP " + response);
                long length = connection.getContentLength();
                if (length > 150L * 1024L * 1024L) throw new IllegalStateException("APK is unexpectedly large");
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    long total = 0;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > 150L * 1024L * 1024L) throw new IllegalStateException("APK is unexpectedly large");
                        out.write(buffer, 0, count);
                    }
                    out.getFD().sync();
                }
                validateApk(target, version);
                downloadedApk = target;
                runOnUiThread(() -> {
                    publishState("Обновление загружено. Открываем установщик Android…", true, version);
                    launchInstaller();
                });
            } catch (Exception e) {
                publishState("Не удалось загрузить/проверить APK. Попробуйте позже.", true, version);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void validateApk(File apk, String versionTag) throws Exception {
        PackageInfo info;
        int signingFlags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 33) {
            info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(signingFlags));
        } else {
            //noinspection deprecation
            info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags);
        }
        if (info == null || !RELEASE_PACKAGE.equals(info.packageName)) {
            throw new SecurityException("APK package ID mismatch");
        }
        long downloadedCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        long expectedCode = versionCodeFromTag(versionTag);
        if (downloadedCode <= BuildConfig.VERSION_CODE || downloadedCode != expectedCode) {
            throw new SecurityException("APK version does not match the release tag");
        }
    }

    private void launchInstaller() {
        if (downloadedApk == null || !downloadedApk.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            publishState("Разрешите установку неизвестных приложений для GP Guide в настройках Android, затем вернитесь сюда.", true, latestVersionName);
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settings, REQUEST_INSTALL_PERMISSION);
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", downloadedApk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            publishState("Не удалось открыть системный установщик Android.", true, latestVersionName);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) launchInstaller();
            else publishState("Разрешение не предоставлено. Обновление можно установить позже.", true, latestVersionName);
        }
    }

    private void publishState(String message, boolean available, String version) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safeMessage = JSONObject.quote(message);
            String safeVersion = JSONObject.quote(version == null ? "" : version);
            webView.evaluateJavascript("window.updateState && window.updateState(" + safeMessage + "," + available + "," + safeVersion + ");", null);
        });
    }

    public final class UpdateBridge {
        @JavascriptInterface
        public void checkForUpdates() { MainActivity.this.checkForUpdates(); }

        @JavascriptInterface
        public void installAvailableUpdate() { MainActivity.this.downloadUpdate(); }

        @JavascriptInterface
        public String getVersionName() { return BuildConfig.VERSION_NAME; }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.removeJavascriptInterface("AppUpdates");
        background.shutdownNow();
        super.onDestroy();
    }
}
