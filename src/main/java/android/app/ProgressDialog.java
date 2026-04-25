package android.app;

import android.content.Context;

public class ProgressDialog extends Dialog {

    public ProgressDialog() {
        super();
    }

    public ProgressDialog(Context context) {
        super(context);
    }

    public static ProgressDialog show(Context context, CharSequence title, CharSequence message) {
        return new ProgressDialog(context);
    }

    public static ProgressDialog show(Context context, CharSequence title, CharSequence message, boolean indeterminate) {
        return new ProgressDialog(context);
    }

    public static ProgressDialog show(Context context, CharSequence title, CharSequence message, boolean indeterminate, boolean cancelable) {
        return new ProgressDialog(context);
    }

    public void setMessage(CharSequence message) {
    }

    public void setCancelable(boolean flag) {
    }
}
