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
    private record Fixture(String owner, String container, String firstEditor, String secondEditor,
        String containerField, String model, String modelField, String keyGetter, String closure,
        String delegate, String sender, String photoSender, String listener, String ownerSender,
        String pipeline, String videoCallback, String isVideo, String sessionField, String recipients) { }
    private static Fixture fixture(ClassLoader loader) throws ClassNotFoundException {
        try {
            Class<?> owner = loader.loadClass("X.0q6");
            if (owner.getField("A0t").getType() == loader.loadClass("com.instagram.common.session.UserSession"))
                return new Fixture("X.0q6", "X.13o", "A0N", "A0O", "A0O", "X.Wdf", "A0E", "CT8", "X.9zT",
                    "X.10T", "X.10V", "X.14w", "X.15C", "A12", "X.10n", "GHJ", "EVf", "A0t", "DcA");
        } catch (ReflectiveOperationException ignored) { }
        try {
            loader.loadClass("X.5Ta");
            return new Fixture("X.5Ta", "X.5c5", "A0O", "A0P", "A0O", "X.Czk", "A0E", "CF1", "X.6Bk",
                "X.5Vd", "X.5Ve", "X.5d9", "X.5dN", "A13", "X.5Vk", "FyJ", "EFt", null, null);
        } catch (ClassNotFoundException ignored) {
            loader.loadClass("X.01SB");
            return new Fixture("X.01SB", "X.01XZ", "A0N", "A0O", "A0P", "X.0Vaj", "A0F", "CXi", "X.0BfO",
                "X.01TK", "X.01TL", "X.01Yw", "X.01ZC", "A14", "X.01Ti", "GUG", "Eeo", "A0v", "DjA");
        }
    }
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
                    // Construct a sparse fixture for the installed APK. Production thread
                    // binding discovers these ownership relationships structurally.
                    Fixture fixture = fixture(loader);
                    Object owner = blank(loader, fixture.owner()); Object container = blank(loader, fixture.container());
                    set(container,fixture.firstEditor(),view); set(container,fixture.secondEditor(),view); set(owner,fixture.containerField(),container);
                    Object[] key = { keyType.getConstructor(String.class, java.util.List.class).newInstance("synthetic-thread-a", null) };
                    Class<?> modelType = Class.forName(fixture.model(), false, loader);
                    Object model = Proxy.newProxyInstance(loader, new Class<?>[]{modelType}, (p,m,a) -> m.getName().equals(fixture.keyGetter()) ? key[0] : null);
                    set(owner,fixture.modelField(),model);
                    Object closure = blank(loader,fixture.closure()); set(closure,"A00",owner);
                    Object delegate = blank(loader,fixture.delegate()); set(delegate,"A09",closure);
                    Object controller = blank(loader,fixture.sender()); set(controller,"A03",delegate);
                    Object sender = blank(loader,fixture.photoSender()); set(sender,"A01",new WeakReference<>(controller));
                    Object listener = blank(loader,fixture.listener());
                    Object gallerySender = blank(loader,fixture.sender()); set(owner,fixture.ownerSender(),gallerySender);
                    boolean senderLinked=false;
                    for(Field f:listener.getClass().getDeclaredFields()) if(f.getType()==sender.getClass()) {f.setAccessible(true);f.set(listener,sender);senderLinked=true;}
                    if(!senderLinked) throw new IllegalStateException("Native listener sender shape changed");
                    Method configure = null;
                    for(Method m:composer.getMethods()) if(m.getName().equals("setInputContentInfoListener")) configure=m;
                    configure.invoke(view,new String[]{"image/png","image/jpeg","image/gif"},listener);
                    InstagramComposerPolicy policy = new InstagramComposerPolicy(loader);
                    // The real composer can install its listener before the controller
                    // publishes its editor. Explicit opening must recover that same listener.
                    set(container,fixture.firstEditor(),null); set(container,fixture.secondEditor(),null);
                    policy.configured(view,listener,configure.getParameterTypes()[1]);
                    report.put("initial_unresolved_controller_has_no_capability", policy.viewMimeTypes(view).length == 0);
                    report.put("unresolved_controller_reason_is_specific", policy.videoStatus().equals("thread_unresolved"));
                    set(container,fixture.firstEditor(),view); set(container,fixture.secondEditor(),view);
                    policy.configuredFromEditor(view, true);
                    report.put("explicit_open_recovers_late_controller_without_new_listener", policy.identity(host,view) != null &&
                        java.util.Arrays.asList(policy.viewMimeTypes(view)).contains("video/mp4"));
                    report.put("recovered_controller_reason_is_ready", policy.videoStatus().equals("ready"));
                    policy.configured(view,listener,configure.getParameterTypes()[1]);
                    String original = policy.identity(host,view);
                    report.put("dm_resource_verified",policy.isComposer(view));
                    report.put("native_controller_binding",original!=null);
                    if(original==null)throw new IllegalStateException("Production adapter could not bind real Instagram metadata");
                    InstagramGalleryContract gallery = InstagramGalleryContract.load(loader);
                    if (gallery == null) throw new IllegalStateException("Native gallery contract changed");
                    report.put("native_gallery_sender_owned", gallery.sender(owner) == gallerySender);
                    InstagramAnimatedContract animated = InstagramAnimatedContract.load(loader);
                    report.put("animated_upload_contract_available", animated != null);
                    if (fixture.sessionField() != null && animated == null) {
                        // Report the exact rejected signature in the isolated fixture only.
                        Field shapes = InstagramAnimatedContract.class.getDeclaredField("SHAPES"); shapes.setAccessible(true);
                        for (Object shape : (Object[]) shapes.get(null)) {
                            Method hostSymbols = shape.getClass().getDeclaredMethod("host"); hostSymbols.setAccessible(true);
                            Object symbols = hostSymbols.invoke(shape);
                            Method ownerName = symbols.getClass().getDeclaredMethod("owner"); ownerName.setAccessible(true);
                            if (!ownerName.invoke(symbols).equals(fixture.owner())) continue;
                            Constructor<?> constructor = InstagramAnimatedContract.class.getDeclaredConstructor(ClassLoader.class, shape.getClass());
                            constructor.setAccessible(true); constructor.newInstance(loader, shape);
                        }
                    }
                    if (animated != null) {
                        set(owner, fixture.sessionField(), blank(loader, "com.instagram.common.session.UserSession"));
                        report.put("animated_gif_capability", java.util.Arrays.asList(policy.additionalMimeTypes(view)).contains("image/gif"));
                        report.put("animated_gif_status_ready", policy.gifStatus().equals("ready"));
                        set(owner, fixture.ownerSender(), null);
                        report.put("animated_gif_independent_of_gallery", java.util.Arrays.asList(policy.additionalMimeTypes(view)).equals(java.util.List.of("image/gif")));
                        set(owner, fixture.ownerSender(), gallerySender);
                        Object copied = InstagramThreadBinding.discover(listener, view, keyType).copyKey(view);
                        report.put("recipient_key_is_copied", copied != key[0] && copied.equals(key[0]));
                        // Remove the synthetic session before any native service could use it.
                        set(owner, fixture.sessionField(), null);
                        report.put("missing_animated_session_disabled", !java.util.Arrays.asList(policy.additionalMimeTypes(view)).contains("image/gif"));
                        report.put("missing_animated_session_reason", policy.gifStatus().equals("session_missing"));
                    }
                    set(gallerySender, "A01", host.getApplicationContext());
                    set(gallerySender, "A05", blank(loader, fixture.pipeline()));
                    Field delegateField = gallerySender.getClass().getField("A03");
                    delegateField.setAccessible(true);
                    java.util.concurrent.atomic.AtomicInteger videoCallbacks = new java.util.concurrent.atomic.AtomicInteger();
                    Object recordingVideoDelegate = Proxy.newProxyInstance(loader, new Class<?>[]{delegateField.getType()}, (p,m,a) -> {
                        if (m.getName().equals(fixture.videoCallback())) videoCallbacks.incrementAndGet();
                        return m.getReturnType() == boolean.class ? false : null;
                    });
                    delegateField.set(gallerySender, recordingVideoDelegate);
                    boolean[] permitted = {true};
                    Object guardedSender = gallery.guardedSender(gallerySender, () -> permitted[0]);
                    Object guardedDelegate = delegateField.get(guardedSender);
                    Method videoCallback = null;
                    for (Method method : delegateField.getType().getMethods()) if (method.getName().equals(fixture.videoCallback())) videoCallback = method;
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
                    java.util.List<Runnable> pending = new java.util.ArrayList<>();
                    InstagramComposerPolicy loading = new InstagramComposerPolicy(loader, pending::add);
                    report.put("gallery_loading_reason_is_specific", loading.videoStatus().equals("gallery_loading"));
                    pending.forEach(Runnable::run);
                    report.put("native_video_view_without_keyboard", java.util.Arrays.asList(videos.viewMimeTypes(view)).contains("video/mp4"));
                    set(listener, "A04", true);
                    report.put("native_media_invite_limit_respected", videos.additionalMimeTypes(view).length == 0);
                    report.put("native_media_restriction_reason_is_specific", videos.videoStatus().equals("media_restricted"));
                    set(listener, "A04", false);
                    report.put("video_capability_without_version_gate", java.util.Arrays.asList(videos.additionalMimeTypes(view)).contains("video/mp4"));
                    set(owner, fixture.ownerSender(), null);
                    report.put("missing_video_sender_disabled", videos.additionalMimeTypes(view).length == 0);
                    report.put("missing_video_sender_reason_is_specific", videos.videoStatus().equals("sender_missing"));
                    set(owner, fixture.ownerSender(), gallerySender);
                    // Construct the real native gallery item only. Never invoke the sender.
                    // File IO and metadata extraction run outside this Main callback below.
                    report.put("gallery_contract_verified", true);
                    key[0]=keyType.getConstructor(String.class,java.util.List.class).newInstance("synthetic-thread-b",null);
                    report.put("same_editor_chat_change_detected", !original.equals(policy.identity(host,view)));
                    set(container,fixture.firstEditor(),null); set(container,fixture.secondEditor(),null);
                    report.put("replaced_editor_rejected",policy.identity(host,view)==null);
                    set(container,fixture.firstEditor(),view); set(container,fixture.secondEditor(),view);
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
                                report.put(extension + "_gallery_item_is_video", medium.getClass().getMethod(fixture(loader).isVideo()).invoke(medium));
                                report.put(extension + "_gallery_duration_matches", medium.getClass().getMethod("getDuration").invoke(medium).equals(video.durationMs));
                                report.put(extension + "_gallery_dimensions_match", medium.getClass().getField("A0D").getInt(medium) == video.width && medium.getClass().getField("A05").getInt(medium) == video.height);
                            } catch (Exception error) { try { report.put("error", error.toString()); } catch (Exception ignored) { } }
                        });
                    } finally { clip.delete(); }
                }
                try (InstagramGifFile gif = InstagramGifFile.prepare(getTargetContext(),
                        () -> getTargetContext().getAssets().open("floating/hello.gif"))) {
                    ClassLoader loader = host.createPackageContext("com.instagram.android", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
                    InstagramAnimatedContract animated = InstagramAnimatedContract.load(loader);
                    if (animated != null) {
                        InstagramAnimatedContract.LocalAnimation local = animated.localAnimation(gif);
                        Object descriptor = local.metadata();
                        report.put("local_animation_type", descriptor.getClass().getField("A02").get(descriptor).toString());
                        report.put("local_animation_file_path", descriptor.getClass().getMethod("A02").invoke(descriptor).equals(gif.file.getAbsolutePath()));
                        report.put("local_animation_not_sticker", !descriptor.getClass().getField("A0C").getBoolean(descriptor));
                        Object key = loader.loadClass("com.instagram.model.direct.DirectThreadKey").getConstructor(String.class, java.util.List.class)
                            .newInstance("synthetic-gif-thread", null);
                        Object command = animated.command(local, key, animated.request(null), null);
                        report.put("local_animation_command_uses_pending_media", command.getClass().getField("A03").get(command) == descriptor);
                        report.put("local_animation_command_has_original_recipient", command.getClass().getMethod(fixture(loader).recipients()).invoke(command).equals(java.util.List.of(key)));
                        try (java.io.InputStream original = getTargetContext().getAssets().open("floating/hello.gif")) {
                            report.put("gif_bytes_preserved", java.util.Arrays.equals(Files.readAllBytes(gif.file.toPath()), original.readAllBytes()));
                        }
                    }
                }
            }
            host.finish(); Files.write(new File(getTargetContext().getFilesDir(),"native-report.json").toPath(),report.toString(2).getBytes());
            result.putString("report",report.toString());finish(report.has("error")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
