package android.content;

import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Context {

    public static final int MODE_PRIVATE = 0;

    private static final Map<String, SharedPreferences> PREFS = new ConcurrentHashMap<>();
    private static final Resources RESOURCES = new Resources();
    private static final ContentResolver CONTENT_RESOLVER = new ContentResolver();
    private static final ApplicationInfo APPLICATION_INFO = new ApplicationInfo();

    public Context getApplicationContext() {
        return AppGlobals.getInitialApplication() == null ? this : AppGlobals.getInitialApplication();
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        String key = (name == null || name.isBlank()) ? "default" : name;
        return PREFS.computeIfAbsent(key, ignored -> new SharedPreferences.InMemorySharedPreferences());
    }

    public String getPackageName() {
        return "com.github.tvbox.osc.tk";
    }

    public ApplicationInfo getApplicationInfo() {
        return APPLICATION_INFO;
    }

    public File getFilesDir() {
        File dir = baseDir().resolve(Path.of(".cache", "android-files", getPackageName())).toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File configFile = new File(dir, "config.json");
        if (!configFile.exists()) {
            try {
                java.nio.file.Files.writeString(configFile.toPath(), "{}");
            } catch (java.io.IOException ignore) {
            }
        }
        File tvDir = new File(dir, "TV");
        if (!tvDir.exists()) {
            tvDir.mkdirs();
        }
        return dir;
    }

    public File getCacheDir() {
        File dir = baseDir().resolve(Path.of(".cache", "android-cache", getPackageName())).toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getExternalFilesDir(String type) {
        return getFilesDir();
    }

    public Resources getResources() {
        return RESOURCES;
    }

    public ContentResolver getContentResolver() {
        return CONTENT_RESOLVER;
    }

    private Path baseDir() {
        String configured = System.getProperty("tvbox.app.base");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("TVBOX_APP_BASE");
        }
        if (configured == null || configured.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
