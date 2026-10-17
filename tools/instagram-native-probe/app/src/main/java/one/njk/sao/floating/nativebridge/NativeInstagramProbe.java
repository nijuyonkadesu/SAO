package one.njk.sao.floating.nativebridge;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.file.Files;
import org.json.JSONObject;
import one.njk.sao.instagramprobe.ProbeActivity;

/** Sparse, synthetic metadata in installed Instagram classes; never runs its sending listener. */
public final class NativeInstagramProbe extends Instrumentation {
    private Object unsafe;
    private Method allocate;
    private Object blank(ClassLoader loader, String name) throws Exception {
        return allocate.invoke(unsafe, Class.forName(name, false, loader));
    }
    private static void set(Object object, String field, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(field); f.setAccessible(true); f.set(object, value);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle();
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            unsafe = singleton.get(null); allocate = unsafeType.getMethod("allocateInstance", Class.class);
            Activity host = startActivitySync(new Intent(getTargetContext(), ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                try {
                    Context ig = host.createPackageContext("com.instagram.android", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    ClassLoader loader = ig.getClassLoader();
                    android.content.res.Resources resources = new android.content.res.Resources(ig.getAssets(), ig.getResources().getDisplayMetrics(), ig.getResources().getConfiguration()) {
                        @Override public int getColor(int id, android.content.res.Resources.Theme theme) { return id == 0 ? android.graphics.Color.GRAY : super.getColor(id, theme); }
                    };
                    Context themed = new ContextThemeWrapper(ig, android.R.style.Theme_Material_Light_NoActionBar) {
                        @Override public android.content.res.Resources getResources() { return resources; }
                    };
                    Class<?> composer = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, loader);
                    Class<?> keyType = Class.forName("com.instagram.model.direct.DirectThreadKey", false, loader);
                    EditText view = (EditText) composer.getConstructor(Context.class).newInstance(themed);
                    view.setId(resources.getIdentifier("row_thread_composer_edittext", "id", "com.instagram.android"));
                    host.setContentView(view); view.requestFocus();
                    // These inspected version-specific names are only in this test harness. The
                    // production adapter discovers type/ownership relationships without them.
                    Object owner = blank(loader, "X.01SB"); Object container = blank(loader, "X.01XZ");
                    set(container,"A0N",view); set(container,"A0O",view); set(owner,"A0P",container);
                    Object[] key = { keyType.getConstructor(String.class, java.util.List.class).newInstance("synthetic-thread-a", null) };
                    Class<?> modelType = Class.forName("X.0Vaj", false, loader);
                    Object model = Proxy.newProxyInstance(loader, new Class<?>[]{modelType}, (p,m,a) -> m.getName().equals("CXi") ? key[0] : null);
                    set(owner,"A0F",model);
                    Object closure = blank(loader,"X.0BfO"); set(closure,"A00",owner);
                    Object delegate = blank(loader,"X.01TK"); set(delegate,"A09",closure);
                    Object controller = blank(loader,"X.01TL"); set(controller,"A03",delegate);
                    Object sender = blank(loader,"X.01Yw"); set(sender,"A01",new WeakReference<>(controller));
                    Object listener = blank(loader,"X.01ZC");
                    Object gallerySender = blank(loader,"X.01TL"); set(owner,"A14",gallerySender);
                    boolean senderLinked=false;
                    for(Field f:listener.getClass().getDeclaredFields()) if(f.getType()==sender.getClass()) {f.setAccessible(true);f.set(listener,sender);senderLinked=true;}
                    if(!senderLinked) throw new IllegalStateException("Native listener sender shape changed");
                    Method configure = null;
                    for(Method m:composer.getMethods()) if(m.getName().equals("setInputContentInfoListener")) configure=m;
                    configure.invoke(view,new String[]{"image/png","image/jpeg","image/gif"},listener);
                    InstagramComposerPolicy policy = new InstagramComposerPolicy(loader);
                    policy.configured(view,listener,configure.getParameterTypes()[1]);
                    String original = policy.identity(host,view);
                    report.put("dm_resource_verified",policy.isComposer(view));
                    report.put("native_controller_binding",original!=null);
                    if(original==null)throw new IllegalStateException("Production adapter could not bind real Instagram metadata");
                    InstagramGalleryContract gallery = InstagramGalleryContract.load(loader);
                    if (gallery == null) throw new IllegalStateException("Native gallery contract changed");
                    report.put("native_gallery_sender_owned", gallery.sender(owner) == gallerySender);
                    set(gallerySender, "A01", host.getApplicationContext());
                    set(gallerySender, "A05", blank(loader, "X.01Ti"));
                    Field delegateField = gallerySender.getClass().getField("A03");
                    delegateField.setAccessible(true);
                    java.util.concurrent.atomic.AtomicInteger videoCallbacks = new java.util.concurrent.atomic.AtomicInteger();
                    Object recordingVideoDelegate = Proxy.newProxyInstance(loader, new Class<?>[]{delegateField.getType()}, (p,m,a) -> {
                        if (m.getName().equals("GUG")) videoCallbacks.incrementAndGet();
                        return m.getReturnType() == boolean.class ? false : null;
                    });
                    delegateField.set(gallerySender, recordingVideoDelegate);
                    boolean[] permitted = {true};
                    Object guardedSender = gallery.guardedSender(gallerySender, () -> permitted[0]);
                    Object guardedDelegate = delegateField.get(guardedSender);
                    Method videoCallback = null;
                    for (Method method : delegateField.getType().getMethods()) if (method.getName().equals("GUG")) videoCallback = method;
                    Object[] callbackArgs = {null, null, null, null, null, "synthetic-video", null, 1, false};
                    videoCallback.invoke(guardedDelegate, callbackArgs);
                    videoCallback.invoke(guardedDelegate, callbackArgs);
                    report.put("native_video_callback_once", videoCallbacks.get() == 1);
                    permitted[0] = false;
                    Object cancelled = gallery.guardedSender(gallerySender, () -> permitted[0]);
                    videoCallback.invoke(delegateField.get(cancelled), callbackArgs);
                    permitted[0] = true;
                    videoCallback.invoke(delegateField.get(cancelled), callbackArgs);
                    report.put("changed_chat_callback_dropped_without_retry", videoCallbacks.get() == 1);
                    report.put("original_native_gallery_unchanged", delegateField.get(gallerySender) == recordingVideoDelegate && guardedSender != gallerySender);
                    InstagramComposerPolicy videos = new InstagramComposerPolicy(loader);
                    videos.configured(view, listener, configure.getParameterTypes()[1]);
                    report.put("native_video_capability", new org.json.JSONArray(videos.additionalMimeTypes(view)));
                    set(listener, "A04", true);
                    report.put("native_media_invite_limit_respected", videos.additionalMimeTypes(view).length == 0);
                    set(listener, "A04", false);
                    report.put("video_capability_without_version_gate", java.util.Arrays.asList(videos.additionalMimeTypes(view)).contains("video/mp4"));
                    set(owner, "A14", null);
                    report.put("missing_video_sender_disabled", videos.additionalMimeTypes(view).length == 0);
                    set(owner, "A14", gallerySender);
                    // Construct the real native gallery item only. Never invoke the sender.
                    // File IO and metadata extraction run outside this Main callback below.
                    report.put("gallery_contract_verified", true);
                    key[0]=keyType.getConstructor(String.class,java.util.List.class).newInstance("synthetic-thread-b",null);
                    report.put("same_editor_chat_change_detected", !original.equals(policy.identity(host,view)));
                    set(container,"A0N",null); set(container,"A0O",null);
                    report.put("replaced_editor_rejected",policy.identity(host,view)==null);
                    set(container,"A0N",view); set(container,"A0O",view);
                    policy.reset();
                    report.put("inactive_controller_binding_cleared",policy.identity(host,view)==null);
                    policy.configuredFromEditor(view);
                    report.put("reactivation_recovers_existing_listener",policy.identity(host,view)!=null);
                    configure.invoke(view,null,null);
                    report.put("cleared_listener_rejected",policy.identity(host,view)==null);
                    android.view.inputmethod.EditorInfo info=new android.view.inputmethod.EditorInfo();
                    info.contentMimeTypes=new String[]{"image/png","image/jpeg","image/gif","video/mp4"};
                    report.put("native_allowed_mime_types",new org.json.JSONArray(policy.mimeTypes(info)));
                    // Exercise the unmodified keyboard wrapper only with a recording callback.
                    java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
                    Object recorder=Proxy.newProxyInstance(loader,new Class<?>[]{configure.getParameterTypes()[1]},(p,m,a)->{
                        if(m.getDeclaringClass()!=Object.class)callbacks.incrementAndGet();
                        return m.getReturnType()==boolean.class?true:null;
                    });
                    configure.invoke(view,new String[]{"image/png","image/jpeg"},recorder);
                    android.view.inputmethod.InputConnection ic=view.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                    boolean accepted=ic.commitContent(new android.view.inputmethod.InputContentInfo(
                        android.net.Uri.parse("content://one.njk.sao.instagramprobe.fixtures/still.png"),
                        new ClipDescription("fixture",new String[]{"image/png"}),null),0,null);
                    report.put("real_commit_content_accepted",accepted).put("recording_callbacks",callbacks.get());
                    report.put("scope","Real Instagram classes, synthetic controller/key metadata, recording callback. No authenticated DM, network upload or send.");
                } catch(Throwable error) {
                    Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                    try {report.put("error",cause.toString());java.io.StringWriter trace=new java.io.StringWriter();cause.printStackTrace(new java.io.PrintWriter(trace));report.put("stack",trace.toString());} catch(Exception ignored){}
                }
            });
            if (!report.has("error")) {
                for (String extension : java.util.List.of("mp4", "webm")) {
                    File clip = new File(getTargetContext().getCacheDir(), "native-video-fixture." + extension);
                    try (java.io.InputStream input = getTargetContext().getAssets().open("preview-video." + extension);
                         java.io.OutputStream output = new java.io.FileOutputStream(clip)) { input.transferTo(output); }
                    try (InstagramVideoFile video = InstagramVideoFile.prepare(getTargetContext(), android.net.Uri.fromFile(clip), "video/" + extension)) {
                        runOnMainSync(() -> {
                            try {
                                ClassLoader loader = host.createPackageContext("com.instagram.android", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
                                Object selected = InstagramGalleryContract.load(loader).selectedVideo(video);
                                Object medium = selected.getClass().getField("A00").get(selected);
                                report.put(extension + "_gallery_item_is_video", medium.getClass().getMethod("Eeo").invoke(medium));
                                report.put(extension + "_gallery_duration_matches", medium.getClass().getMethod("getDuration").invoke(medium).equals(video.durationMs));
                                report.put(extension + "_gallery_dimensions_match", medium.getClass().getField("A0D").getInt(medium) == video.width && medium.getClass().getField("A05").getInt(medium) == video.height);
                            } catch (Exception error) { try { report.put("error", error.toString()); } catch (Exception ignored) { } }
                        });
                    } finally { clip.delete(); }
                }
            }
            host.finish(); Files.write(new File(getTargetContext().getFilesDir(),"native-report.json").toPath(),report.toString(2).getBytes());
            result.putString("report",report.toString());finish(report.has("error")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
