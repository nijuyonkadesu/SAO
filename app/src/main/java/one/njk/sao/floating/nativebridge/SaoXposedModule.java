package one.njk.sao.floating.nativebridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.inputmethod.InputMethodManager;
import android.content.ComponentName;
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
import java.util.Arrays;
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
    private boolean resumed;
    private Context host;
    private volatile boolean bound;
    private final java.util.concurrent.Executor bridgeIo = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "SAO bridge"); worker.setDaemon(true); return worker;
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private String modulePackage;
    private String processName;
    private boolean hooksInstalled;
    private INativeStickerClient.Stub client;
    private final Set<View> focusObserved = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
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
                    configureHost(current.getClassLoader());
                }
                return chain.proceed();
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                Activity current = (Activity) chain.getArg(0);
                if (current.getPackageName().equals(processName)) {
                    activity = new WeakReference<>(current); resumed = true;
                    configureHost(current.getClassLoader());
                    connect(current.getApplicationContext());
                    View decor = current.getWindow().getDecorView();
                    if (focusObserved.add(decor)) decor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> publishState());
                    main.post(() -> { scan(current.getWindow().getDecorView(), current); publishState(); });
                    main.postDelayed(() -> { scan(current.getWindow().getDecorView(), current); publishState(); }, 300);
                }
                return result;
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) == activity.get()) { resumed = false; publishState(); }
                return result;
            });
            hook(Instrumentation.class.getDeclaredMethod("callActivityOnDestroy", Activity.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) == activity.get()) {
                    if (editorSession != null) editorSession.clear();
                    activity.clear(); resumed = false; publishState();
                }
                return result;
            });
            hooksInstalled = true;
            configureHost(param.getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn("Host lifecycle hooks unavailable: " + error.getClass().getSimpleName()); }
    }
    private int configurationAttempts;
    private boolean adapterReady;
    private void configureHost(ClassLoader loader) {
        if (adapterReady || configurationAttempts++ >= 3) return;
        try {
            Class<?> composer;
            if (processName.equals("com.instagram.android")) {
                InstagramComposerPolicy instagram = new InstagramComposerPolicy(loader);
                policy = instagram;
                editorSession = new NativeEditorSession(policy, modulePackage);
                composer = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, loader);
                for (Method method : composer.getMethods()) {
                    if (!method.getName().equals("setInputContentInfoListener") || method.getParameterCount() != 2 ||
                        method.getParameterTypes()[0] != String[].class || !installed.add(method)) continue;
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        EditText view = (EditText) chain.getThisObject();
                        if (editorSession.owns(view)) editorSession.clear();
                        instagram.configured(view, chain.getArg(1), method.getParameterTypes()[1]);
                        publishState();
                        if (chain.getArg(1) != null) reconnectFocused(view);
                        return result;
                    });
                }
            } else if (processName.equals("com.discord")) {
                policy = new DiscordComposerPolicy(loader);
                editorSession = new NativeEditorSession(policy, modulePackage);
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
        if (!view.isAttachedToWindow() || !view.hasFocus()) return;
        // Request a natural IME connection after late listener configuration or hook discovery.
        // Never call onCreateInputConnection ourselves or fabricate the host's wrapper.
        main.post(() -> {
            Activity active = activity.get();
            if (active != null && resumed && active.hasWindowFocus() && view.isAttachedToWindow() && view.hasFocus() &&
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
                try { hostVersion = context.getPackageManager().getPackageInfo(processName, 0).versionName; }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                Intent intent = new Intent().setComponent(new ComponentName(modulePackage,
                    "one.njk.sao.floating.nativebridge.NativeBridgeService"));
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) { bound = false; }
            if (!bound) main.post(() -> {
                // Visibility bootstrap may race the first resume. At most three bind attempts
                // per resume, with backoff. No media insertion is retried.
                if (attempt < 2 && resumed) main.postDelayed(() -> {
                    if (resumed) bind(context, attempt + 1);
                }, attempt == 0 ? 250 : 1000);
                else warn("Bridge bind rejected for " + processName);
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
        @Override public void onServiceDisconnected(ComponentName name) { /* Android reconnects this binding. */ }
        @Override public void onBindingDied(ComponentName name) { unbind(); if (resumed) connect(host); }
        @Override public void onNullBinding(ComponentName name) { unbind(); warn("Bridge returned no binder"); }
    };
    private void scan(View view, Activity current) {
        if (current != activity.get() || !resumed || policy == null || editorSession == null) return;
        if (view instanceof EditText && policy.isComposer((EditText) view)) {
            try {
                if (installConnectionHook(view.getClass().getMethod("onCreateInputConnection", EditorInfo.class))) reconnectFocused((EditText) view);
            }
            catch (ReflectiveOperationException | RuntimeException error) { editorSession.clear(); }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i), current);
        }
    }
    private boolean installConnectionHook(Method method) {
        if (!installed.add(method)) return false;
        hook(method).intercept(chain -> {
            Object result = chain.proceed();
            if (!(chain.getThisObject() instanceof EditText)) return result;
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
        String[] packages = host == null ? null : host.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null || !Arrays.asList(packages).contains(modulePackage))
            throw new SecurityException("Unexpected native caller");
    }
    private final class HostClient extends INativeStickerClient.Stub {
        @Override public Bundle snapshot(boolean beginSelection) {
            enforceCaller();
            // Binder never waits for the UI thread. Coalesced refreshes validate in-place DM changes.
            if (refreshQueued.compareAndSet(false, true))
                main.post(() -> { try { publishState(); } finally { refreshQueued.set(false); } });
            return new Bundle(cachedState);
        }
        @Override public boolean insert(Uri uri, String mimeType, String expected, long requestId) {
            enforceCaller();
            if (uri == null || !"content".equals(uri.getScheme()) || !(modulePackage + ".fileprovider").equals(uri.getAuthority())) return false;
            boolean[] accepted = {false};
            AtomicBoolean expired = new AtomicBoolean(false);
            CountDownLatch ready = new CountDownLatch(1);
            main.post(() -> {
                try {
                    if (!expired.get() && editorSession != null) accepted[0] = editorSession.insert(activity.get(), resumed, uri, mimeType, expected, requestId);
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
