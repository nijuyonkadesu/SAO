package X;

import android.os.Parcel;
import android.os.Parcelable;
import android.widget.EditText;
import java.lang.ref.WeakReference;

/** Synthetic DM closure graph: no host account or sending code. */
public final class NativeBridgeFixtures {
    public static final class Key implements Parcelable {
        public String id;
        public Key(String id) { this.id = id; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel out, int flags) { out.writeString(id); out.writeString(null); out.writeStringList(null); }
        public static final Creator<Key> CREATOR = new Creator<>() {
            @Override public Key createFromParcel(Parcel in) { return new Key(in.readString()); }
            @Override public Key[] newArray(int size) { return new Key[size]; }
        };
    }
    public static final class Container {
        public EditText editor;
        public Container(EditText editor) { this.editor = editor; }
    }
    public static final class Owner {
        public Key key;
        public Container container;
        public Owner(EditText editor, String id) { key = new Key(id); container = new Container(editor); }
        public static Key readKey(Owner owner) { return owner.key; }
    }
    public static final class Link {
        public final Object captured;
        public Link(Object value) { captured = value; }
    }
    public static final class Pair {
        public final Object first;
        public final Object second;
        public Pair(Object first, Object second) { this.first = first; this.second = second; }
    }
    public static Object listener(Object owner) {
        return new Link(new Link(new WeakReference<>(new Link(new Link(new Link(owner))))));
    }
}
