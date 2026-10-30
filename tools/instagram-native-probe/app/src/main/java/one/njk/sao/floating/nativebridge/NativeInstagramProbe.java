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
    private void initializeCodecRuntime(Context instagram, ClassLoader loader) throws Exception {
        Context fixture = getTargetContext();
        // Only the APK/native-library paths come from Instagram. SoLoader's writable
        // directories belong to this account-free probe, never to the installed app.
        Context runtime = new ContextWrapper(instagram) {
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
                android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo(super.getApplicationInfo());
                info.dataDir = fixture.getApplicationInfo().dataDir;
                return info;
            }
            @Override public File getDataDir() { return fixture.getDataDir(); }
            @Override public File getFilesDir() { return fixture.getFilesDir(); }
            @Override public File getCacheDir() { return fixture.getCacheDir(); }
            @Override public File getDir(String name, int mode) { return fixture.getDir(name, mode); }
        };
        Method init = loader.loadClass("X.3xZ").getDeclaredMethod("A06", Context.class, loader.loadClass("X.FvN"), int.class);
        init.setAccessible(true); init.invoke(null, runtime, null, 0);
        Class<?> archiveType = loader.loadClass("X.3z4");
        Method getArchive = archiveType.getDeclaredMethod("A00"); getArchive.setAccessible(true);
        Object archive = getArchive.invoke(null);
        Method getReader = archiveType.getDeclaredMethod("A01"); getReader.setAccessible(true);
        Constructor<?> source = loader.loadClass("X.3z6").getConstructor(Context.class, loader.loadClass("X.77z"), archiveType, File.class, String.class);
        Object packed = source.newInstance(runtime, getReader.invoke(archive), archive, fixture.getDir("codec-libs", 0), "base");
        Method prepend = loader.loadClass("X.3z0").getDeclaredMethod("A02", loader.loadClass("X.3y0"), String.class);
        prepend.setAccessible(true); prepend.invoke(null, packed, "lib-compressed");
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
                    try { new InstagramStickerDispatch(loader).installed(); }
                    catch (ReflectiveOperationException ignored) { }
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
                        Object capturedSession=blank(loader,"com.instagram.common.session.UserSession");
                        set(owner, fixture.sessionField(), capturedSession);
                        String capturedIdentity=policy.identity(host,view);
                        NativeContentPreparation captured=policy.captureContent(getTargetContext(),view,
                            android.net.Uri.parse("content://one.njk.sao.instagramprobe.fileprovider/fixture.gif"),InstagramStickerFormat.RENDITION);
                        report.put("native_sticker_captures_recipient_before_preparation",captured!=null && captured.hasCapturedRecipient());
                        policy.configured(view,listener,configure.getParameterTypes()[1]);
                        report.put("listener_recreation_keeps_same_native_recipient",policy.sameCapturedRecipient(view,capturedIdentity,owner,capturedSession));
                        set(owner,fixture.sessionField(),blank(loader,"com.instagram.common.session.UserSession"));
                        report.put("recreated_listener_rejects_changed_account",!policy.sameCapturedRecipient(view,capturedIdentity,owner,capturedSession));
                        set(owner,fixture.sessionField(),capturedSession);
                        report.put("animated_gif_capability", java.util.Arrays.asList(policy.additionalMimeTypes(view)).contains(InstagramStickerFormat.RENDITION));
                        report.put("animated_gif_status_ready", policy.gifStatus().equals("ready"));
                        set(owner, fixture.ownerSender(), null);
                        report.put("animated_gif_independent_of_gallery", java.util.Arrays.asList(policy.additionalMimeTypes(view)).equals(java.util.Arrays.asList(InstagramStickerFormat.renditions())));
                        set(owner, fixture.ownerSender(), gallerySender);
                        Object copied = InstagramThreadBinding.discover(listener, view, keyType).copyKey(view);
                        report.put("recipient_key_is_copied", copied != key[0] && copied.equals(key[0]));
                        // Remove the synthetic session before any native service could use it.
                        set(owner, fixture.sessionField(), null);
                        report.put("missing_animated_session_disabled", !java.util.Arrays.asList(policy.additionalMimeTypes(view)).contains(InstagramStickerFormat.RENDITION));
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
                try (InstagramAnimationFile gif = InstagramAnimationFile.prepareGif(getTargetContext(),
                        () -> getTargetContext().getAssets().open("floating/hello.gif"))) {
                    Context instagram = host.createPackageContext("com.instagram.android", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    ClassLoader loader = instagram.getClassLoader();
                    InstagramAnimatedContract animated = InstagramAnimatedContract.load(loader);
                    if (animated != null) {
                        initializeCodecRuntime(instagram, loader);
                        StaticStickerProbe.verify(getTargetContext(), new InstagramStickerEncoder(loader), animated, report);
                        Context mediaContext=NativeModuleResources.mediaContext(instagram,
                            android.net.Uri.parse("content://"+getTargetContext().getPackageName()+".fileprovider/fixture"));
                        int shader=getTargetContext().getResources().getIdentifier("vertex_shader_transformation_es2","raw",getTargetContext().getPackageName());
                        byte[] expectedShader, actualShader, foreignShader;
                        try(java.io.InputStream input=getTargetContext().getResources().openRawResource(shader)) { expectedShader=input.readAllBytes(); }
                        try(java.io.InputStream input=mediaContext.getResources().openRawResource(shader)) { actualShader=input.readAllBytes(); }
                        try(java.io.InputStream input=instagram.getResources().openRawResource(shader)) { foreignShader=input.readAllBytes(); }
                        catch(android.content.res.Resources.NotFoundException expected) { foreignShader=new byte[0]; }
                        report.put("injected_shader_resources_match_module",java.util.Arrays.equals(expectedShader,actualShader));
                        report.put("foreign_context_cannot_supply_module_shader",!java.util.Arrays.equals(expectedShader,foreignShader));
                        report.put("shader_context_keeps_native_host_identity",mediaContext.getPackageName().equals(instagram.getPackageName()) &&
                            mediaContext.getApplicationInfo().dataDir.equals(instagram.getApplicationInfo().dataDir));
                        for (String extension : java.util.List.of("mp4", "webm", "hd", "odd", "apng")) {
                            File clip = new File(getTargetContext().getCacheDir(), "sticker-video-fixture." + extension);
                            String asset = switch (extension) {
                                case "hd" -> "sticker-hd.mp4";
                                case "odd" -> "sticker-odd.mp4";
                                case "apng" -> "software-animation.png";
                                default -> "preview-video." + extension;
                            };
                            try (java.io.InputStream in = getTargetContext().getAssets().open(asset);
                                 java.io.OutputStream out = new java.io.FileOutputStream(clip)) { in.transferTo(out); }
                            File resultFile = new InstagramStickerEncoder(loader).convertVideo(mediaContext, clip);
                            try (InstagramAnimationFile videoSticker = InstagramAnimationFile.prepareWebp(getTargetContext(), resultFile)) {
                                report.put(extension + "_direct_sticker_decodable", videoSticker.width > 0 && videoSticker.height > 0);
                                if (extension.equals("odd")) report.put("software_sticker_preserves_odd_width", videoSticker.width == 357 && videoSticker.height == 372);
                                if (extension.equals("apng")) report.put("animated_png_sticker_preserves_canvas", videoSticker.width == 64 && videoSticker.height == 48);
                                Files.copy(videoSticker.file.toPath(), new File(getTargetContext().getFilesDir(), "native-video-" + extension + ".webp").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } finally { clip.delete(); resultFile.delete(); }
                        }
                        File encoded = new InstagramStickerEncoder(loader).convert(gif.file);
                        try (InstagramAnimationFile webp = InstagramAnimationFile.prepareWebp(getTargetContext(), encoded)) {
                        report.put("native_webp_encoded", webp.file.length() > 0 && webp.mimeType.equals("image/webp"));
                        report.put("native_webp_dimensions_preserved", webp.width == gif.width && webp.height == gif.height);
                        Files.copy(webp.file.toPath(), new File(getTargetContext().getFilesDir(), "native-sticker-fixture.webp").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        InstagramAnimatedContract.LocalAnimation local = animated.localAnimation(webp);
                        Object descriptor = local.metadata();
                        report.put("local_animation_type", descriptor.getClass().getField("A02").get(descriptor).toString());
                        report.put("local_animation_file_path", descriptor.getClass().getMethod("A02").invoke(descriptor).equals(webp.file.getAbsolutePath()));
                        report.put("local_animation_is_sticker", descriptor.getClass().getField("A0C").getBoolean(descriptor));
                        report.put("sticker_upload_uses_truthful_webp_mime", local.pending().getClass().getField("A4r").get(local.pending()).equals("image/webp"));
                        report.put("sticker_upload_uses_native_webp_format", local.pending().getClass().getField("A0I").getInt(local.pending()) == 6);
                        Object key = loader.loadClass("com.instagram.model.direct.DirectThreadKey").getConstructor(String.class, java.util.List.class)
                            .newInstance("synthetic-gif-thread", null);
                        boolean unuploadedRejected = false;
                        try { animated.command(local.pending(), key, animated.request(null), null); }
                        catch (IllegalStateException expected) { unuploadedRejected = true; }
                        report.put("gif_command_rejects_unuploaded_media", unuploadedRejected);
                        Object originalAttachment = local.pending().getClass().getField("A3O").get(local.pending());
                        local.pending().getClass().getField("A3O").set(local.pending(), 42L);
                        Object command = animated.command(local.pending(), key, animated.request(null), null);
                        report.put("native_riff_command_type", command.getClass().getMethod("getTypeName").invoke(command).equals("configure_riff_sticker_message"));
                        report.put("local_animation_command_has_original_recipient", command.getClass().getMethod(fixture(loader).recipients()).invoke(command).equals(java.util.List.of(key)));
                        Object animation = command.getClass().getField("A01").get(command);
                        report.put("sticker_configuration_uses_actual_upload_id", animation.getClass().getField("A0F").get(animation).equals("42"));
                        Object ownerSession = blank(loader, "com.instagram.common.session.UserSession");
                        InstagramStickerDispatch dispatcher = InstagramStickerDispatch.forCommand(command.getClass());
                        try (AutoCloseable registered = dispatcher.register(command, ownerSession, local.pending())) {
                            report.put("completed_gif_attachment_can_be_registered", true);
                            Object handler = blank(loader, "X.5aw");
                            set(handler, "A00", blank(loader, "com.instagram.common.session.UserSession"));
                            InstagramStickerDispatch.Claim wrongAccount = dispatcher.claim(handler, command);
                            report.put("gif_attachment_rejects_other_account", wrongAccount.owned() && wrongAccount.pending() == null);
                            java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
                            Class<?> callbackType = loader.loadClass("X.Wbb");
                            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (p, m, a) -> {
                                if (m.getName().equals("FdI") && a[0] != null && a[1] == null &&
                                    !a[0].getClass().getField("A0D").getBoolean(a[0]) &&
                                    !a[0].getClass().getField("A0E").getBoolean(a[0])) rejected.incrementAndGet();
                                return null;
                            });
                            try (AutoCloseable invalid = dispatcher.register(command, ownerSession, local.pending())) {
                                report.put("gif_context_rejection_completes_native_callback", dispatcher.prepareInvocation(handler, callback, command).rejected() && rejected.get() == 1);
                                report.put("gif_context_rejection_is_once", !dispatcher.prepareInvocation(handler, callback, command).owned() && rejected.get() == 1);
                            }
                            set(handler, "A00", ownerSession);
                            try (AutoCloseable valid = dispatcher.register(command, ownerSession, local.pending())) {
                                Object other = animated.command(local.pending(), key, animated.request(null), null);
                                report.put("gif_attachment_rejects_unregistered_request", !dispatcher.claim(handler, other).owned());
                                report.put("gif_attachment_claim_preserves_uploaded_pending", dispatcher.claim(handler, command).pending() == local.pending());
                                report.put("gif_attachment_claim_is_once", !dispatcher.claim(handler, command).owned());
                            }
                        } finally { local.pending().getClass().getField("A3O").set(local.pending(), originalAttachment); }
                        } finally { encoded.delete(); }
                        try (InstagramAnimationFile source = InstagramAnimationFile.prepareGif(getTargetContext(),
                                () -> getTargetContext().getAssets().open("floating/disposal.gif"))) {
                            File converted = new InstagramStickerEncoder(loader).convert(source.file);
                            try (InstagramAnimationFile webp = InstagramAnimationFile.prepareWebp(getTargetContext(), converted)) {
                                report.put("disposal_fixture_is_decodable", webp.width == 64 && webp.height == 64);
                                Files.copy(webp.file.toPath(), new File(getTargetContext().getFilesDir(), "native-disposal-fixture.webp").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } finally { converted.delete(); }
                        }
                        try (java.io.InputStream original = getTargetContext().getAssets().open("floating/hello.gif")) {
                            report.put("gif_bytes_preserved", java.util.Arrays.equals(Files.readAllBytes(gif.file.toPath()), original.readAllBytes()));
                        }
                    }
                }
            }
            java.util.List<String> failedChecks = new java.util.ArrayList<>();
            for (var keys = report.keys(); keys.hasNext();) {
                String name = keys.next();
                if (Boolean.FALSE.equals(report.get(name))) failedChecks.add(name);
            }
            if (!failedChecks.isEmpty()) report.put("error", "Failed checks: " + String.join(", ", failedChecks));
            host.finish(); Files.write(new File(getTargetContext().getFilesDir(),"native-report.json").toPath(),report.toString(2).getBytes());
            result.putString("report",report.toString());finish(report.has("error")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }catch(Throwable error){
            java.io.StringWriter trace=new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("error",trace.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
}
