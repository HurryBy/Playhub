package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class LinearLayout extends ViewGroup {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public float weight;

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }

    public LinearLayout() {
        super();
    }

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
    }
}
