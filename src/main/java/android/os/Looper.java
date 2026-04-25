package android.os;

public class Looper {

    private static final Looper MAIN = new Looper();

    public static void prepare() {
    }

    public static void prepareMainLooper() {
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }
}
