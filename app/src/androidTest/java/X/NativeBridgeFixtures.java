package X;

import android.os.Parcel;
import android.os.Parcelable;
import android.widget.EditText;
import java.lang.ref.WeakReference;

/** Synthetic DM closure graph: no host account or sending code. */
public final class NativeBridgeFixtures {
    public static final class WhatsappEditor extends EditText {
        public final android.os.Bundle conversation = new android.os.Bundle();
        public final android.os.Bundle other = new android.os.Bundle();
        private android.content.res.Resources roleResources;
        public WhatsappEditor(android.content.Context context) {
            super(context);
            android.content.res.Resources resources = context.getResources();
            roleResources = new android.content.res.Resources(resources.getAssets(), resources.getDisplayMetrics(), resources.getConfiguration()) {
                @Override public String getResourceEntryName(int id) { return "entry"; }
            };
        }
        @Override public android.content.res.Resources getResources() {
            return roleResources == null ? super.getResources() : roleResources;
        }
    }
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
    public static final class Closure implements kotlin.jvm.functions.Function0<Object> {
        public final Object captured;
        Closure(Object owner) { captured = owner; }
        public Object invoke() { return captured; }
    }
    public static final class Delegate {
        public final kotlin.jvm.functions.Function0<Object> callback;
        Delegate(Object owner) { callback = new Closure(owner); }
    }
    public static final class Noise {
        public final Object a, b, c, d, e, f, g, h;
        Noise(int depth) {
            a = leaf(depth); b = leaf(depth); c = leaf(depth); d = leaf(depth);
            e = leaf(depth); f = leaf(depth); g = leaf(depth); h = leaf(depth);
        }
        private static Object leaf(int depth) { return depth == 0 ? new Link(new Object()) : new Noise(depth - 1); }
    }
    public static final class Controller {
        public final Delegate delegate;
        public final Noise mediaA = new Noise(1), mediaB = new Noise(1), mediaC = new Noise(1), mediaD = new Noise(1);
        Controller(Object owner) { delegate = new Delegate(owner); }
    }
    public static Object noisyListener(Object owner) {
        return new Link(new Link(new WeakReference<>(new Controller(owner))));
    }
    public static Object listener(Object owner) {
        return new Link(new Link(new WeakReference<>(new Link(new Link(new Link(owner))))));
    }
}
