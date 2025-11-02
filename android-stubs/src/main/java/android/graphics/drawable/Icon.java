package android.graphics.drawable;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

public final class Icon {
    private Icon() {
    }

    public static Icon createWithResource(Context context, int resId) {
        return new Icon();
    }

    public static Icon createWithBitmap(Bitmap bitmap) {
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
