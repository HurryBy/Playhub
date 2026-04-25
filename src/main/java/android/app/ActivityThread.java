package android.app;

import java.util.LinkedHashMap;
import java.util.Map;

public class ActivityThread {

    public static final class ActivityClientRecord {
        public boolean paused;
        public Activity activity;

        public ActivityClientRecord(Activity activity) {
            this.activity = activity;
            this.paused = false;
        }
    }

    private static final ActivityThread CURRENT = new ActivityThread();

    public final Map<Object, ActivityClientRecord> mActivities = new LinkedHashMap<>();
    public Application mInitialApplication;

    public static ActivityThread currentActivityThread() {
        return CURRENT;
    }

    public static Application currentApplication() {
        return CURRENT.mInitialApplication;
    }

    static void setApplication(Application application) {
        CURRENT.mInitialApplication = application;
    }

    static void registerActivity(Activity activity) {
        CURRENT.mActivities.put(activity.hashCode(), new ActivityClientRecord(activity));
        if (CURRENT.mInitialApplication == null) {
            CURRENT.mInitialApplication = new Application();
        }
    }
}
