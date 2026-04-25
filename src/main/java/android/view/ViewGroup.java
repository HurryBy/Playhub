package android.view;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class ViewGroup extends View {

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private final List<View> children = new ArrayList<>();

    public ViewGroup() {
        super();
    }

    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View view) {
        children.add(view);
    }

    public void addView(View view, LayoutParams params) {
        view.setLayoutParams(params);
        children.add(view);
    }
}
