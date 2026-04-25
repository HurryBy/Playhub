package android.preference;

import android.content.Context;
import android.content.SharedPreferences;

public final class PreferenceManager {

    private PreferenceManager() {
    }

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return context.getSharedPreferences("default", 0);
    }
}
