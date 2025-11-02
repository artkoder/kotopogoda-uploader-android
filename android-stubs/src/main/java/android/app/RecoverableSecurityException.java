package android.app;

public class RecoverableSecurityException extends SecurityException {
    private final RemoteAction userAction;

    public RecoverableSecurityException(Throwable cause, CharSequence message, RemoteAction userAction) {
        super(message != null ? message.toString() : null, cause);
        this.userAction = userAction;
    }

    public RemoteAction getUserAction() {
        return userAction;
    }
}
