package android.app;

import android.content.Context;

public class Dialog {

    protected final Context context;

    public Dialog() {
        this(null);
    }

    public Dialog(Context context) {
        this.context = context;
    }

    public void show() {
    }

    public void dismiss() {
    }

    public boolean isShowing() {
        return false;
    }
}
