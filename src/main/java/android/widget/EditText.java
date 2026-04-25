package android.widget;

import android.content.Context;
import android.text.Editable;

public class EditText extends TextView {

    private final SimpleEditable editable = new SimpleEditable("");

    public EditText() {
        super();
    }

    public EditText(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text) {
        super.setText(text);
        editable.set(text == null ? "" : text.toString());
    }

    public Editable getText() {
        return editable;
    }

    public void setHint(CharSequence hint) {
    }

    private static final class SimpleEditable implements Editable {
        private String value;

        private SimpleEditable(String value) {
            this.value = value;
        }

        private void set(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return value.subSequence(start, end);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
