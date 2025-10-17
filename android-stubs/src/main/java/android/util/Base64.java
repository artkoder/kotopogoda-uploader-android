package android.util;

public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;

    private Base64() {
    }

    public static byte[] decode(String value, int flags) {
        return java.util.Base64.getDecoder().decode(value);
    }

    public static String encodeToString(byte[] input, int flags) {
        if ((flags & NO_PADDING) != 0) {
            return java.util.Base64.getEncoder().withoutPadding().encodeToString(input);
        }
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
