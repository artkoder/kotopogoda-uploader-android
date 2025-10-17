package android.net;

import java.net.URISyntaxException;

public class Uri {
    private final java.net.URI delegate;

    private Uri(java.net.URI delegate) {
        this.delegate = delegate;
    }

    public static Uri parse(String value) {
        try {
            return new Uri(new java.net.URI(value));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid URI: " + value, exception);
        }
    }

    public String getLastPathSegment() {
        String path = delegate.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        int index = path.lastIndexOf('/');
        String segment = index >= 0 ? path.substring(index + 1) : path;
        return segment.isEmpty() ? null : segment;
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Uri other = (Uri) obj;
        return delegate.equals(other.delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }
}
