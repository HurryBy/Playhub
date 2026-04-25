package android.provider;

import android.content.ContentResolver;

public final class Settings {

    private Settings() {
    }

    public static final class Secure {

        private Secure() {
        }

        public static String getString(ContentResolver resolver, String name) {
            if ("android_id".equals(name)) {
                return "a0437b95410bcd47";
            }
            return "";
        }
    }
}
