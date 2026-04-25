package android.util;

public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    private Base64() {
    }

    public static byte[] decode(String input, int flags) {
        if ((flags & URL_SAFE) == URL_SAFE) {
            return java.util.Base64.getUrlDecoder().decode(input);
        }
        return java.util.Base64.getDecoder().decode(input);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(new String(input), flags);
    }

    public static String encodeToString(byte[] input, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) == URL_SAFE
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_WRAP) == NO_WRAP || (flags & NO_PADDING) == NO_PADDING) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encodeToString(input);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encodeToString(input, flags).getBytes();
    }
}
