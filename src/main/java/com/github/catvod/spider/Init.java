package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.widget.EditText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Init {

    public static String C = "";
    public static int E4 = 0;
    public static String G9 = "";
    public static Boolean K = Boolean.FALSE;
    public static String OA = "";
    public static int V = 0;
    public static String d = "";
    public static String dt = "";
    public static String i = "";
    public static String v = "";
    public static String xo = "";

    private static final Init INSTANCE = new Init();
    private static final Application APPLICATION = new Application();
    private static final Activity ACTIVITY = new Activity();
    private static final EditText EDIT_TEXT = new EditText();
    private static final Map<String, Boolean> KEYWORDS = new ConcurrentHashMap<>();
    private static final int EXECUTOR_THREADS = Math.max(2, Integer.getInteger("tvbox.init.executorThreads", 2));
    private static final int MAX_PENDING_TASKS = Math.max(64, Integer.getInteger("tvbox.init.maxPendingTasks", 256));
    private static final AtomicInteger PENDING_TASKS = new AtomicInteger();
    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(EXECUTOR_THREADS, r -> {
        Thread thread = new Thread(r, "tvbox-init");
        thread.setDaemon(true);
        return thread;
    });

    static {
        EXECUTOR.setRemoveOnCancelPolicy(true);
        EXECUTOR.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        EXECUTOR.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public static void a() throws IOException {
    }

    public static void checkPermission() {
    }

    static EditText C() {
        return EDIT_TEXT;
    }

    public static AlertDialog N0() {
        return new AlertDialog();
    }

    static void OA() {
    }

    public static Application context() {
        return APPLICATION;
    }

    static void dt(String value) {
        d = value == null ? "" : value;
    }

    public static String d(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    public static void e(InputStream inputStream, String path) throws IOException {
        if (inputStream == null || path == null || path.isBlank()) {
            return;
        }
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (OutputStream outputStream = Files.newOutputStream(target.toPath())) {
            inputStream.transferTo(outputStream);
        }
    }

    public static Init get() {
        return INSTANCE;
    }

    public static Activity getActivity() {
        return ACTIVITY;
    }

    public static Activity getConfigActivity() {
        return ACTIVITY;
    }

    public static Map<String, Boolean> getKeywordsMap() {
        return KEYWORDS;
    }

    public static InputStream i(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return InputStream.nullInputStream();
        }
        File file = new File(path);
        if (!file.exists()) {
            return InputStream.nullInputStream();
        }
        return Files.newInputStream(file.toPath());
    }

    public static void init(Context context) {
    }

    public static void interceptActivityStart() {
    }

    public static void lj() {
    }

    public static void post(Runnable runnable) {
        submitAsync(runnable, 0);
    }

    public static void execute(Runnable runnable) {
        post(runnable);
    }

    public static void replaceCloudDiskNames() {
    }

    public static void run(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    public static void run(Runnable runnable, int delayMillis) {
        submitAsync(runnable, Math.max(0, delayMillis));
    }

    public static void startFloatBall() {
    }

    public static void startGoProxy(Context context) {
    }

    public static void show(String message) {
    }

    public static void write(File file, InputStream inputStream) throws IOException {
        if (file == null || inputStream == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (OutputStream outputStream = Files.newOutputStream(file.toPath())) {
            inputStream.transferTo(outputStream);
        }
    }

    private static void submitAsync(Runnable runnable, long delayMillis) {
        if (runnable == null) {
            return;
        }
        if (!tryReserveSlot()) {
            return;
        }
        Runnable guarded = () -> {
            try {
                runnable.run();
            } finally {
                PENDING_TASKS.decrementAndGet();
            }
        };
        try {
            if (delayMillis > 0) {
                EXECUTOR.schedule(guarded, delayMillis, TimeUnit.MILLISECONDS);
            } else {
                EXECUTOR.execute(guarded);
            }
        } catch (RejectedExecutionException ex) {
            PENDING_TASKS.decrementAndGet();
        }
    }

    private static boolean tryReserveSlot() {
        while (true) {
            int current = PENDING_TASKS.get();
            if (current >= MAX_PENDING_TASKS) {
                return false;
            }
            if (PENDING_TASKS.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
