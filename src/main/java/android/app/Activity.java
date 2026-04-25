package android.app;

import android.content.ComponentName;
import android.content.Context;

public class Activity extends Context {

    public Activity() {
        ActivityThread.registerActivity(this);
    }

    public ComponentName getComponentName() {
        return new ComponentName(getPackageName(), getClass().getName());
    }

    public Application getApplication() {
        return AppGlobals.getInitialApplication();
    }

    public int checkSelfPermission(String permission) {
        return 0;
    }

    public void requestPermissions(String[] permissions, int requestCode) {
    }
}
