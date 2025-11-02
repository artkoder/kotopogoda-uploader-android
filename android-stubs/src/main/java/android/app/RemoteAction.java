package android.app;

import android.graphics.drawable.Icon;

public final class RemoteAction {
    private final Icon icon;
    private final CharSequence title;
    private final CharSequence contentDescription;
    private PendingIntent actionIntent;
    private boolean enabled = true;
    private boolean shouldShowIcon = true;

    public RemoteAction(Icon icon, CharSequence title, CharSequence contentDescription, PendingIntent intent) {
        this.icon = icon;
        this.title = title;
        this.contentDescription = contentDescription;
        this.actionIntent = intent;
    }

    public Icon getIcon() {
        return icon;
    }

    public CharSequence getTitle() {
        return title;
    }

    public CharSequence getContentDescription() {
        return contentDescription;
    }

    public PendingIntent getActionIntent() {
        return actionIntent;
    }

    public void setActionIntent(PendingIntent intent) {
        this.actionIntent = intent;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean shouldShowIcon() {
        return shouldShowIcon;
    }

    public void setShouldShowIcon(boolean shouldShowIcon) {
        this.shouldShowIcon = shouldShowIcon;
    }
}
