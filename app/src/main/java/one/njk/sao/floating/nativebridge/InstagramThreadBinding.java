package one.njk.sao.floating.nativebridge;

import android.os.Parcel;
import android.os.Parcelable;
import android.widget.EditText;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Resolves the DM controller captured by the editor's own media listener. Discovery reads
 * bounded closure fields once, never messages, databases or collections. Obfuscated names
 * are not pinned: the controller must expose the named DirectThreadKey type and own this
 * exact editor. Missing/ambiguous shapes disable insertion.
 */
final class InstagramThreadBinding {
    private final WeakReference<Object> owner;
    private final Method keyReader;
    private final Field containerField;
    private final List<Field> editorFields;
    private final Class<?> keyType;

    private InstagramThreadBinding(Object owner, Method reader, Field container, List<Field> editors, Class<?> keyType) {
        this.owner = new WeakReference<>(owner); this.keyReader = reader;
        this.containerField = container; this.editorFields = editors; this.keyType = keyType;
    }
    private record Node(Object value, int depth, boolean fromReference) {}

    static InstagramThreadBinding discover(Object listener, EditText view, Class<?> keyType) {
        ArrayDeque<Node> queue = new ArrayDeque<>(); queue.add(new Node(listener, 0, false));
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        InstagramThreadBinding found = null;
        int visited = 0;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst(); Object value = node.value();
            if (value == null || !seen.add(value)) continue;
            if (++visited > 128) return null; // Incomplete inspection cannot establish uniqueness.
            if (value instanceof Reference<?>) { queue.addFirst(new Node(((Reference<?>) value).get(), node.depth(), true)); continue; }
            if (!value.getClass().getName().startsWith("X.")) continue;
            try {
                Method reader = keyReader(value.getClass(), keyType);
                if (reader != null) {
                    InstagramThreadBinding candidate = ownedEditor(value, reader, view, keyType);
                    if (candidate != null) {
                        if (found != null && found.owner.get() != value) return null;
                        found = candidate;
                    }
                    continue; // Do not walk controller models or conversation contents.
                }
                if (node.depth() >= 5) continue;
                List<Field> captures = fields(value.getClass());
                boolean references = captures.stream().anyMatch(f -> Reference.class.isAssignableFrom(f.getType()));
                boolean functions = captures.stream().anyMatch(f -> f.getType().getName().startsWith("kotlin.jvm.functions."));
                for (Field field : captures) {
                    if (!Modifier.isFinal(field.getModifiers())) continue;
                    // Listener helpers hold weak references; their controller's delegate holds
                    // Kotlin closures. Follow those relationships, not media stores or models.
                    if (references && !Reference.class.isAssignableFrom(field.getType())) continue;
                    if (functions && !field.getType().getName().startsWith("kotlin.jvm.functions.")) continue;
                    Object child = field.get(value);
                    if (child == null) continue;
                    if (child instanceof Reference<?>) {
                        queue.addLast(new Node(child, node.depth() + 1, false));
                    } else if (child.getClass().getName().startsWith("X.")) {
                        if (node.fromReference() && captures.size() > 3 && !hasFunctionCaptures(child.getClass()) &&
                            keyReader(child.getClass(), keyType) == null) continue;
                        queue.addLast(new Node(child, node.depth() + 1, false));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return null; }
        }
        return found;
    }
    private static boolean hasFunctionCaptures(Class<?> type) {
        for (Field field : fields(type))
            if (field.getType().getName().startsWith("kotlin.jvm.functions.")) return true;
        for (Class<?> contract : type.getInterfaces())
            if (contract.getName().startsWith("kotlin.jvm.functions.")) return true;
        return false;
    }
    private static Method keyReader(Class<?> ownerType, Class<?> keyType) {
        Method found = null;
        for (Method method : ownerType.getDeclaredMethods()) {
            if (method.getReturnType() != keyType || !Modifier.isStatic(method.getModifiers()) ||
                method.getParameterCount() != 1 || method.getParameterTypes()[0] != ownerType) continue;
            if (found != null) throw new IllegalStateException("Ambiguous thread getter");
            method.setAccessible(true); found = method;
        }
        return found;
    }
    private static InstagramThreadBinding ownedEditor(Object owner, Method reader, EditText view, Class<?> keyType) throws IllegalAccessException {
        Field container = null; List<Field> editors = null;
        for (Field field : fields(owner.getClass())) {
            if (!field.getType().getName().startsWith("X.")) continue;
            List<Field> candidates = new ArrayList<>();
            for (Field nested : fields(field.getType()))
                if (EditText.class.isAssignableFrom(nested.getType())) candidates.add(nested);
            if (candidates.isEmpty()) continue;
            Object value = field.get(owner); if (value == null) continue;
            boolean owns = false;
            for (Field nested : candidates) if (nested.get(value) == view) owns = true;
            if (!owns) continue;
            if (container != null) return null;
            container = field; editors = candidates;
        }
        return container == null ? null : new InstagramThreadBinding(owner, reader, container, editors, keyType);
    }
    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        // Only application closures; never Android View/Context fields.
        for (Class<?> current = type; current != null && current.getName().startsWith("X."); current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true); result.add(field);
            }
        }
        return result;
    }
    String identity(EditText view) {
        Object current = owner.get(); if (current == null) return null;
        Parcel parcel = Parcel.obtain();
        try {
            Object container = containerField.get(current); if (container == null) return null;
            boolean owns = false;
            for (Field field : editorFields) if (field.get(container) == view) owns = true;
            if (!owns) return null;
            Object key = keyReader.invoke(null, current);
            if (key == null || key.getClass() != keyType || !(key instanceof Parcelable)) return null;
            ((Parcelable) key).writeToParcel(parcel, 0);
            byte[] serialized = parcel.marshall();
            // DirectThreadKey's verified parcel contract: thread ID, v2 ID, recipient IDs.
            // Its empty sentinel does not identify a conversation. Unknown layouts fail closed.
            parcel.setDataPosition(0);
            String thread = parcel.readString(); String v2 = parcel.readString();
            List<String> recipients = parcel.createStringArrayList();
            boolean hasThread = thread != null && !thread.isEmpty() || v2 != null && !v2.isEmpty();
            boolean hasRecipients = recipients != null && !recipients.isEmpty();
            if (!hasThread && !hasRecipients) return null;
            if (hasRecipients) for (String recipient : recipients) if (recipient == null || recipient.isEmpty()) return null;
            return Arrays.toString(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (Exception error) { return null; }
        finally { parcel.recycle(); }
    }
}
