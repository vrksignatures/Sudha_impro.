package android.graphics;

/**
 * Minimal stand-in for android.graphics.Rect, used ONLY by tools/selftest so the real
 * selector classes can be compiled and executed on a plain JVM. Never packaged in the app.
 */
public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() {
    }

    public Rect(int left, int top, int right, int bottom) {
        set(left, top, right, bottom);
    }

    public Rect(Rect other) {
        if (other != null) set(other.left, other.top, other.right, other.bottom);
    }

    public void set(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public void set(Rect other) {
        set(other.left, other.top, other.right, other.bottom);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public int centerX() {
        return (left + right) >> 1;
    }

    public int centerY() {
        return (top + bottom) >> 1;
    }

    public float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public boolean contains(int x, int y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean intersect(Rect r) {
        if (left < r.right && r.left < right && top < r.bottom && r.top < bottom) {
            if (left < r.left) left = r.left;
            if (top < r.top) top = r.top;
            if (right > r.right) right = r.right;
            if (bottom > r.bottom) bottom = r.bottom;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")";
    }
}
