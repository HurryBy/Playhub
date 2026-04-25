package android.os;

import java.io.File;

public class Environment {

    public static File getExternalStorageDirectory() {
        File dir = new File(".cache/android-external");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
