package android.widget;

import android.content.Context;
import android.view.View;

public class TextView extends View {

    private CharSequence text = "";

    public TextView() {
        super();
    }

    public TextView(Context context) {
        super(context);
    }

    public void setText(CharSequence text) {
        this.text = text == null ? "" : text;
    }

    public CharSequence getText() {
        return text;
    }

    public void setTextColor(int color) {
    }

    public void setTextSize(float size) {
    }
}
