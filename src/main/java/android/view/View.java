package android.view;

import android.content.Context;

public class View {

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public interface OnClickListener {
        void onClick(View v);
    }

    public interface OnLongClickListener {
        boolean onLongClick(View v);
    }

    protected Context context;
    private OnClickListener onClickListener;
    private OnLongClickListener onLongClickListener;
    private int visibility = VISIBLE;
    private ViewGroup.LayoutParams layoutParams;

    public View() {
    }

    public View(Context context) {
        this.context = context;
    }

    public void setOnClickListener(OnClickListener listener) {
        this.onClickListener = listener;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        this.onLongClickListener = listener;
    }

    public boolean performClick() {
        if (onClickListener != null) {
            onClickListener.onClick(this);
            return true;
        }
        return false;
    }

    public boolean performLongClick() {
        return onLongClickListener != null && onLongClickListener.onLongClick(this);
    }

    public void setVisibility(int visibility) {
        this.visibility = visibility;
    }

    public int getVisibility() {
        return visibility;
    }

    public void setLayoutParams(ViewGroup.LayoutParams layoutParams) {
        this.layoutParams = layoutParams;
    }

    public ViewGroup.LayoutParams getLayoutParams() {
        return layoutParams;
    }

    public void requestFocus() {
    }

    public void setPadding(int left, int top, int right, int bottom) {
    }

    public void setBackgroundColor(int color) {
    }

    public Context getContext() {
        return context == null ? new Context() : context;
    }
}
