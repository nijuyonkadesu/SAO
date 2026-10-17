package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.inputmethod.InputMethodManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

/** Modern libxposed API 102 entry; ordinary SAO never loads this class. */
public final class SaoXposedModule extends XposedModule {
    private Handler main;
    private final Set<Method> installed = new HashSet<>();
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private ComposerPolicy policy;
    private NativeEditorSession editorSession;
    private volatile boolean resumed;
    private volatile boolean nativeActive;
    private long controlGeneration;
    private boolean controlRegistered;
    private boolean initialHandshake;
    private Context host;
    private volatile boolean bound;
    private volatile int moduleUid = -1;
    private final java.util.concurrent.Executor bridgeIo = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "SAO bridge"); worker.setDaemon(true); return worker;
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private String modulePackage;
    private String processName;
    private boolean hooksInstalled;
    private INativeStickerClient.Stub client;
    private final Set<View> focusObserved = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<View> receiverObserved = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private volatile Bundle cachedState = Bundle.EMPTY;
    private volatile String hostVersion = "";
    private String lastStatus = "";

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        if (param.isSystemServer() || (!processName.equals("com.whatsapp") && !processName.equals("com.whatsapp.w4b") &&
            !processName.equals("com.instagram.android") && !processName.equals("com.discord"))) return;
        main = new Handler(Looper.getMainLooper());
        modulePackage = getModuleApplicationInfo().packageName;
        client = new HostClient();
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (main == null || hooksInstalled || !param.getPackageName().equals(processName)) return;
        // Lifecycle diagnostics and the IPC connection must work even when a host version
        // has changed its composer classes. A failed adapter must not hide bridge activation.
        try {
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnCreate", Activity.class, Bundle.class)).intercept(chain -> {
                Activity current = (Activity) chain.getArg(0);
                if (current.getPackageName().equals(processName)) {
                    activity = new WeakReference<>(current);
                    registerControl(current.getApplicationContext());
                    if (nativeActive) configureHost(current.getClassLoader());
                }
                return chain.proceed();
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                Activity current = (Activity) chain.getArg(0);
                if (current.getPackageName().equals(processName)) {
                    activity = new WeakReference<>(current); resumed = true;
                    registerControl(current.getApplicationContext());
                    if (!nativeActive) {
                        // One startup handshake discovers an already running floating service.
                        // No retries or editor/API work while inactive; signed start broadcasts
                        // wake this process if that initial bind was filtered or SAO was stopped.
                        if (!initialHandshake) { initialHandshake = true; connect(current.getApplicationContext()); }
                        return result;
                    }
                    configureHost(current.getClassLoader());
                    connect(current.getApplicationContext());
                    View decor = current.getWindow().getDecorView();
                    observeWindow(decor, current);
                    main.post(() -> { scan(current.getWindow().getDecorView(), current); publishState(); });
                    main.postDelayed(() -> { scan(current.getWindow().getDecorView(), current); publishState(); }, 300);
                }
                return result;
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) == activity.get()) { resumed = false; if (nativeActive) publishState(); }
                return result;
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnDestroy", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) == activity.get()) {
                    if (editorSession != null) editorSession.clear();
                    activity.clear(); resumed = false; if (nativeActive) publishState();
                }
                return result;
            });
            hooksInstalled = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn("Host lifecycle hooks unavailable: " + error.getClass().getSimpleName()); }
    }
    private void registerControl(Context context) {
        if (controlRegistered) return;
        host = context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                applyActive(intent.getBooleanExtra("active", false), intent.getLongExtra("generation", 0));
            }
        };
        IntentFilter filter = new IntentFilter(modulePackage + ".floating.NATIVE_ACTIVE");
        String senderPermission = modulePackage + ".permission.NATIVE_CONTROL";
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, senderPermission, main, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, senderPermission, main);
        controlRegistered = true;
    }
    private void observeWindow(View decor, Activity current) {
        if (focusObserved.add(decor)) decor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!nativeActive) return;
            if (hasFocus && resumed) scan(decor, current);
            publishState();
        });
    }
    private void applyActive(boolean active, long generation) {
        if (generation < controlGeneration || generation == controlGeneration && nativeActive == active) return;
        boolean newSession = generation > controlGeneration;
        controlGeneration = generation;
        if (!newSession && nativeActive == active) { if (!active) unbind(); return; }
        if (newSession && editorSession != null) editorSession.clear();
        if (newSession && policy != null) policy.reset();
        nativeActive = active;
        if (!active) {
            if (editorSession != null) editorSession.clear();
            cachedState = Bundle.EMPTY;
            unbind(); return;
        }
        Activity current = activity.get();
        if (host != null) connect(host);
        if (current != null && resumed) {
            configureHost(current.getClassLoader());
            View decor = current.getWindow().getDecorView();
            observeWindow(decor, current);
            scan(decor, current);
            // WA/Instagram may have created their IME while SAO was inactive. Reconnect
            // only on this activation, only the original focused supported text field.
            View focused = current.getCurrentFocus();
            if (focused instanceof EditText && policy != null && policy.isComposer((EditText) focused)) reconnectFocused((EditText) focused);
            publishState();
        }
    }
    private int configurationAttempts;
    private boolean adapterReady;
    private void configureHost(ClassLoader loader) {
        if (adapterReady || configurationAttempts++ >= 3) return;
        try {
            Class<?> composer;
            if (processName.equals("com.instagram.android")) {
                InstagramComposerPolicy instagram = new InstagramComposerPolicy(loader, bridgeIo);
                policy = instagram;
                editorSession = new NativeEditorSession(policy, modulePackage);
                composer = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, loader);
                for (Method method : composer.getMethods()) {
                    if (!method.getName().equals("setInputContentInfoListener") || method.getParameterCount() != 2 ||
                        method.getParameterTypes()[0] != String[].class || !installed.add(method)) continue;
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (!nativeActive) return result;
                        EditText view = (EditText) chain.getThisObject();
                        if (editorSession.owns(view)) editorSession.clear();
                        instagram.configured(view, chain.getArg(1), method.getParameterTypes()[1]);
                        publishState();
                        if (chain.getArg(1) != null && instagram.isComposer(view)) reconnectFocused(view);
                        return result;
                    });
                }
            } else if (processName.equals("com.discord")) {
                DiscordComposerPolicy discord = new DiscordComposerPolicy(loader, bridgeIo);
                policy = discord;
                editorSession = new NativeEditorSession(policy, modulePackage);
                Class<?> root = Class.forName("com.discord.chat.input.views.ChatInputRootView", false, loader);
                Method setter = root.getMethod("setListener", Class.forName(root.getName() + "$ChatInputListener", false, loader));
                if (installed.add(setter)) hook(setter).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!nativeActive) return result;
                    EditText editor = discord.configuredEditor(chain.getThisObject());
                    if (editor != null) {
                        if (editorSession.owns(editor)) editorSession.clear();
                        observeReceiver(editor);
                        if (editor.isAttachedToWindow()) scanReceiverAfterLayout();
                    }
                    publishState();
                    return result;
                });
                composer = Class.forName("com.discord.chat.input.views.DCDChatInput", false, loader);
            } else {
                policy = new WhatsappComposerPolicy();
                editorSession = new NativeEditorSession(policy, modulePackage);
                try { composer = Class.forName("com.whatsapp.mentions.ui.MentionableEntry", false, loader); }
                catch (ClassNotFoundException error) { adapterReady = true; return; } // Runtime role scan can install it.
            }
            installConnectionHook(composer.getMethod("onCreateInputConnection", EditorInfo.class));
            adapterReady = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("Host composer unavailable: " + error.getClass().getSimpleName());
        }
    }
    private void reconnectFocused(EditText view) {
        if (!nativeActive || !view.isAttachedToWindow() || !view.hasFocus()) return;
        // Request a natural IME connection after late listener configuration or hook discovery.
        // Never call onCreateInputConnection ourselves or fabricate the host's wrapper.
        main.post(() -> {
            Activity active = activity.get();
            if (nativeActive && active != null && resumed && active.hasWindowFocus() && view.isAttachedToWindow() && view.hasFocus() &&
                view.getRootView() == active.getWindow().getDecorView().getRootView()) {
                InputMethodManager manager = view.getContext().getSystemService(InputMethodManager.class);
                if (manager != null) manager.restartInput(view);
            }
        });
    }
    private void warn(String message) { log(android.util.Log.WARN, "SAO", message); }
    private void connect(Context context) {
        host = context;
        if (!bound) bind(context, 0);
    }
    private void bind(Context context, int attempt) {
        if (bound) return;
        bound = true;
        bridgeIo.execute(() -> {
            try {
                moduleUid = context.getPackageManager().getApplicationInfo(modulePackage, 0).uid;
                try { hostVersion = context.getPackageManager().getPackageInfo(processName, 0).versionName; }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                Intent intent = new Intent().setComponent(new ComponentName(modulePackage,
                    "one.njk.sao.floating.nativebridge.NativeBridgeService"));
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) bound = false;
            } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { bound = false; }
            if (!bound) main.post(() -> {
                // Visibility bootstrap may race the first resume. At most three bind attempts
                // per resume, with backoff. No media insertion is retried.
                if (nativeActive && attempt < 2 && resumed) main.postDelayed(() -> {
                    if (nativeActive && resumed) bind(context, attempt + 1);
                }, attempt == 0 ? 250 : 1000);
                else if (nativeActive) warn("Bridge bind rejected for " + processName);
            });
        });
    }
    private void unbind() {
        if (bound && host != null) try { host.unbindService(connection); }
        catch (IllegalArgumentException ignored) { }
        bound = false;
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bridgeIo.execute(() -> {
                try { INativeBridge.Stub.asInterface(service).registerClient(host.getPackageName(), client); }
                catch (Exception error) { main.post(() -> { unbind(); warn("Bridge registration rejected"); }); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            nativeActive = false;
            if (editorSession != null) editorSession.clear();
            if (policy != null) policy.reset();
            cachedState = Bundle.EMPTY; // No editor work while SAO is gone; Android may reconnect.
        }
        @Override public void onBindingDied(ComponentName name) {
            boolean wasActive = nativeActive;
            onServiceDisconnected(name); unbind();
            if (wasActive) connect(host); // One state handshake, never an inactive retry loop.
        }
        @Override public void onNullBinding(ComponentName name) { unbind(); warn("Bridge returned no binder"); }
    };
    private void observeReceiver(EditText editor) {
        if (!receiverObserved.add(editor)) return;
        editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { scanReceiverAfterLayout(); }
            @Override public void onViewDetachedFromWindow(View view) {
                if (editorSession.owns(editor)) { editorSession.clear(); publishState(); }
            }
        });
        editor.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // A late React editor can attach before it has bounds. Observe its first real
            // layout rather than depending on the activity's earlier resume scan.
            if (nativeActive && !editorSession.owns(editor) && right > left && bottom > top &&
                (oldRight == oldLeft || oldBottom == oldTop)) scanReceiverAfterLayout();
        });
    }
    private void scanReceiverAfterLayout() {
        main.post(() -> {
            Activity active = activity.get();
            if (nativeActive && active != null && resumed) scan(active.getWindow().getDecorView(), active);
            publishState();
        });
    }
    private void scan(View view, Activity current) {
        if (!nativeActive || current != activity.get() || !resumed || policy == null || editorSession == null) return;
        java.util.List<EditText> receivers = new java.util.ArrayList<>();
        scanViews(view, receivers);
        if (receivers.size() == 1) {
            EditText editor = receivers.get(0);
            // Existing leases are validated by snapshot, never silently re-captured when
            // resuming from the picker. A changed draft must revoke the original token.
            if (!editorSession.owns(editor)) editorSession.captureView(current, editor);
        } else if (receivers.size() > 1) editorSession.clear(); // Ambiguous native drafts stay disabled.
    }
    private void scanViews(View view, java.util.List<EditText> receivers) {
        if (view instanceof EditText && view.isShown() && view.isEnabled() && policy.isComposer((EditText) view)) {
            EditText editor = (EditText) view;
            if (policy.viewMimeTypes(editor).length > 0 && editor.getGlobalVisibleRect(new android.graphics.Rect())) {
                receivers.add(editor); observeReceiver(editor);
            }
            try {
                if (installConnectionHook(view.getClass().getMethod("onCreateInputConnection", EditorInfo.class))) reconnectFocused(editor);
            } catch (ReflectiveOperationException | RuntimeException error) { editorSession.clear(); }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scanViews(group.getChildAt(i), receivers);
        }
    }
    private boolean installConnectionHook(Method method) {
        if (!installed.add(method)) return false;
        hook(method).intercept(chain -> {
            Object result = chain.proceed();
            if (!nativeActive || !(chain.getThisObject() instanceof EditText)) return result;
            EditText actual = (EditText) chain.getThisObject();
            Activity active = activity.get();
            if (active != null && policy != null && editorSession != null && actual.hasFocus() && policy.isComposer(actual) &&
                actual.getRootView() == active.getWindow().getDecorView().getRootView() && result instanceof InputConnection) {
                if (policy instanceof InstagramComposerPolicy) ((InstagramComposerPolicy) policy).configuredFromEditor(actual);
                editorSession.capture(active, actual, (InputConnection) result, (EditorInfo) chain.getArg(0));
                publishState();
            }
            return result;
        });
        return true;
    }
    private void publishState() {
        if (!nativeActive) { cachedState = Bundle.EMPTY; return; }
        Bundle state = editorSession == null ? new Bundle() : editorSession.snapshot(activity.get(), resumed);
        if (!adapterReady) state.putString("status", "hooks_unavailable");
        state.putString("host_version", hostVersion);
        String status = state.getString("status", "waiting_editor");
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            log(android.util.Log.INFO, "SAO", processName + " native state: " + status);
        }
        cachedState = state;
    }
    private void enforceCaller() {
        // Resolve the installed module UID once per binding. Snapshots do not query
        // PackageManager repeatedly; arbitrary UIDs still cannot call the native client.
        if (moduleUid < 0 || Binder.getCallingUid() != moduleUid)
            throw new SecurityException("Unexpected native caller");
    }
    private final class HostClient extends INativeStickerClient.Stub {
        private final AtomicBoolean inserting = new AtomicBoolean();
        @Override public Bundle snapshot(boolean beginSelection) {
            enforceCaller();
            // Binder never waits for the UI thread. Coalesced refreshes validate in-place DM changes.
            if (nativeActive && resumed && refreshQueued.compareAndSet(false, true))
                main.post(() -> { try { publishState(); } finally { refreshQueued.set(false); } });
            return new Bundle(cachedState);
        }
        @Override public void setActive(boolean active, long generation) {
            enforceCaller(); main.post(() -> applyActive(active, generation));
        }
        @Override public boolean insert(Uri uri, String mimeType, String expected, long requestId) {
            enforceCaller();
            if (!nativeActive || uri == null || !"content".equals(uri.getScheme()) || !(modulePackage + ".fileprovider").equals(uri.getAuthority())) return false;
            if (!inserting.compareAndSet(false, true)) return false;
            try {
                ComposerPolicy[] selected = new ComposerPolicy[1];
                Context[] context = new Context[1];
                if (!onMain(() -> {
                    Activity current = activity.get();
                    if (current == null || editorSession == null ||
                        !editorSession.accepts(current, resumed, uri, mimeType, expected, requestId)) return false;
                    selected[0] = policy; context[0] = current.getApplicationContext();
                    return selected[0] != null;
                })) return false;
                // Binder worker: copying and metadata extraction never block the host UI.
                try (PreparedNativeContent content = selected[0].prepareContent(context[0], uri, mimeType)) {
                    if (content == null) return false;
                    return onMain(() -> editorSession != null && policy == selected[0] &&
                        editorSession.insert(activity.get(), resumed, uri, mimeType, expected, requestId, content));
                }
            } catch (Exception | LinkageError error) { warn("Native media preparation rejected insertion"); return false; }
            finally { inserting.set(false); }
        }
        private boolean onMain(java.util.function.BooleanSupplier operation) {
            boolean[] accepted = {false};
            AtomicBoolean expired = new AtomicBoolean(false);
            CountDownLatch ready = new CountDownLatch(1);
            main.post(() -> {
                try {
                    if (nativeActive && !expired.get()) accepted[0] = operation.getAsBoolean();
                } catch (RuntimeException error) { warn("Native content handler rejected insertion"); }
                finally { publishState(); ready.countDown(); }
            });
            try {
                boolean completed = ready.await(3, TimeUnit.SECONDS);
                if (!completed) expired.set(true);
                return completed && accepted[0];
            } catch (InterruptedException error) { expired.set(true); Thread.currentThread().interrupt(); return false; }
        }
    }
}
