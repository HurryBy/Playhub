package android.graphics;

import java.io.InputStream;

public final class BitmapFactory {

    public static class Options {
        public int outWidth;
        public int outHeight;
        public int inSampleSize = 1;
    }

    private BitmapFactory() {
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return new Bitmap(length, length);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        if (options != null) {
            options.outWidth = Math.max(1, length);
            options.outHeight = Math.max(1, length);
        }
        return decodeByteArray(data, offset, length);
    }

    public static Bitmap decodeStream(InputStream stream) {
        return new Bitmap(1, 1);
    }
}
