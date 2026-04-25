package android.util;

public final class Log {

    private Log() {
    }

    public static int d(String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        System.out.println(tag + ": " + msg);
        if (tr != null) {
            tr.printStackTrace(System.out);
        }
        return 0;
    }

    public static int i(String tag, String msg) {
        return d(tag, msg);
    }

    public static int w(String tag, String msg) {
        return d(tag, msg);
    }

    public static int e(String tag, String msg) {
        System.err.println(tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.err.println(tag + ": " + msg);
        if (tr != null) {
            tr.printStackTrace(System.err);
        }
        return 0;
    }
}
