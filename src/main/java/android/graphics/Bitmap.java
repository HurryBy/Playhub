package android.graphics;

public class Bitmap {

    private final int width;
    private final int height;

    public Bitmap() {
        this(0, 0);
    }

    public Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
