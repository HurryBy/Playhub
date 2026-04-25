package android.util;

import java.io.Serializable;
import java.util.Objects;

public class Pair<F, S> implements Serializable {

    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A first, B second) {
        return new Pair<>(first, second);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Pair<?, ?> pair)) {
            return false;
        }
        return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public String toString() {
        return "Pair{" + String.valueOf(first) + ", " + String.valueOf(second) + "}";
    }
}
