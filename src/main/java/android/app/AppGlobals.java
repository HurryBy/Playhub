package android.app;

public final class AppGlobals {

    private static Application initialApplication = new Application();

    private AppGlobals() {
    }

    public static Application getInitialApplication() {
        return initialApplication;
    }

    static void setInitialApplication(Application application) {
        if (application != null) {
            initialApplication = application;
        }
    }
}
