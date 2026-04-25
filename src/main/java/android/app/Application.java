package android.app;

import android.content.Context;

public class Application extends Context {

    public Application() {
        ActivityThread.setApplication(this);
        AppGlobals.setInitialApplication(this);
    }
}
