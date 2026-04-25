package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;

import java.util.HashMap;
import java.util.Map;

public class AlertDialog extends Dialog implements DialogInterface {

    private DialogInterface.OnCancelListener onCancelListener;
    private DialogInterface.OnDismissListener onDismissListener;
    private DialogInterface.OnShowListener onShowListener;
    private final Map<Integer, Button> buttons = new HashMap<>();

    public AlertDialog() {
        super();
    }

    public AlertDialog(Context context) {
        super(context);
    }

    public void setOnCancelListener(DialogInterface.OnCancelListener listener) {
        this.onCancelListener = listener;
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.onDismissListener = listener;
    }

    public void setOnShowListener(DialogInterface.OnShowListener listener) {
        this.onShowListener = listener;
    }

    @Override
    public void show() {
        if (onShowListener != null) {
            onShowListener.onShow(this);
        }
    }

    @Override
    public void dismiss() {
        if (onDismissListener != null) {
            onDismissListener.onDismiss(this);
        }
    }

    public void cancel() {
        if (onCancelListener != null) {
            onCancelListener.onCancel(this);
        }
        dismiss();
    }

    public Button getButton(int which) {
        return buttons.computeIfAbsent(which, ignored -> new Button());
    }

    public static class Builder {

        private final AlertDialog dialog;

        public Builder(Context context) {
            this.dialog = new AlertDialog(context);
        }

        public Builder setTitle(CharSequence title) {
            return this;
        }

        public Builder setMessage(CharSequence message) {
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            return this;
        }

        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            dialog.setOnCancelListener(listener);
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            dialog.setOnDismissListener(listener);
            return this;
        }

        public Builder setView(View view) {
            return this;
        }

        public AlertDialog create() {
            return dialog;
        }

        public AlertDialog show() {
            dialog.show();
            return dialog;
        }
    }
}
