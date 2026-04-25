package android.text;

public final class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toString().contentEquals(b);
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Object token : tokens) {
            if (!first) {
                builder.append(delimiter);
            }
            builder.append(token);
            first = false;
        }
        return builder.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                builder.append(delimiter);
            }
            builder.append(tokens[i]);
        }
        return builder.toString();
    }
}
