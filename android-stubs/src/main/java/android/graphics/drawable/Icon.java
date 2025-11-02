package android.graphics.drawable;

import android.net.Uri;

public final class Icon {
    private Icon() {
    }

    public static Icon createWithResource(Object context, int resId) {
        return new Icon();
    }

    public static Icon createWithBitmap(Object bitmap) {
        return new Icon();
    }

    public static Icon createWithContentUri(String uri) {
        return new Icon();
    }

    public static Icon createWithContentUri(Uri uri) {
        return new Icon();
    }

    public static Icon createWithData(byte[] data, int offset, int length) {
        return new Icon();
    }
}
