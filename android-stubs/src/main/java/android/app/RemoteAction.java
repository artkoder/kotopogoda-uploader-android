package android.app;

import android.content.IntentSender;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

public final class RemoteAction implements Parcelable {
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

    protected RemoteAction(Parcel in) {
        icon = null;
        title = in.readString();
        contentDescription = in.readString();
    }

    public static final Creator<RemoteAction> CREATOR = new Creator<RemoteAction>() {
        @Override
        public RemoteAction createFromParcel(Parcel in) {
            return new RemoteAction(in);
        }

        @Override
        public RemoteAction[] newArray(int size) {
            return new RemoteAction[size];
        }
    };

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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title != null ? title.toString() : null);
        dest.writeString(contentDescription != null ? contentDescription.toString() : null);
    }
}
