package one.njk.sao.floating.nativebridge;

import android.app.Activity;
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

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        if (param.isSystemServer() || (!processName.equals("com.whatsapp") && !processName.equals("com.whatsapp.w4b") &&
            !processName.equals("com.instagram.android"))) return;
        main = new Handler(Looper.getMainLooper());
        modulePackage = getModuleApplicationInfo().packageName;
        client = new HostClient();
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (main == null || hooksInstalled || !param.isFirstPackage() || !param.getPackageName().equals(processName)) return;
        try {
            if (processName.equals("com.instagram.android")) {
                InstagramComposerPolicy instagram = new InstagramComposerPolicy(param.getClassLoader());
                policy = instagram;
                Class<?> composer = Class.forName("com.instagram.ui.widget.textview.ComposerAutoCompleteTextView", false, param.getClassLoader());
                // Capture before the first naturally created IME connection. Configuration also
                // revokes a lease when Instagram replaces its listener in the same editor.
                for (Method method : composer.getMethods()) {
                    if (!method.getName().equals("setInputContentInfoListener") || method.getParameterCount() != 2 ||
                        method.getParameterTypes()[0] != String[].class) continue;
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        EditText view = (EditText) chain.getThisObject();
                        if (editorSession.owns(view)) editorSession.clear();
                        instagram.configured(view, chain.getArg(1), method.getParameterTypes()[1]);
                        publishState();
                        return result;
                    });
                }
                installConnectionHook(composer.getMethod("onCreateInputConnection", EditorInfo.class));
            } else {
                policy = new WhatsappComposerPolicy();
                try { installConnectionHook(Class.forName("com.whatsapp.mentions.ui.MentionableEntry", false, param.getClassLoader())
                    .getMethod("onCreateInputConnection", EditorInfo.class)); }
                catch (ReflectiveOperationException | RuntimeException ignored) { /* Runtime scan remains available. */ }
            }
            editorSession = new NativeEditorSession(policy, modulePackage);
            hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class)).intercept(chain -> {
                Activity current = (Activity) chain.getThisObject();
                if (current.getPackageName().equals(processName)) activity = new WeakReference<>(current);
                return chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                Activity current = (Activity) chain.getThisObject();
                if (current.getPackageName().equals(processName)) {
                    activity = new WeakReference<>(current); resumed = true;
                    connect(current.getApplicationContext());
                    View decor = current.getWindow().getDecorView();
                    if (focusObserved.add(decor)) decor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> publishState());
                    main.post(() -> { scan(current.getWindow().getDecorView(), current); publishState(); });
                    main.postDelayed(() -> { scan(current.getWindow().getDecorView(), current); publishState(); }, 300);
                }
                return result;
            });
            hook(Activity.class.getDeclaredMethod("onPause")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() == activity.get()) { resumed = false; publishState(); }
                return result;
            });
            hook(Activity.class.getDeclaredMethod("onDestroy")).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() == activity.get()) { editorSession.clear(); activity.clear(); resumed = false; publishState(); }
                return result;
            });
            hooksInstalled = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { warn("Host hooks unavailable"); }
    }
    private void warn(String message) { log(android.util.Log.WARN, "SAO", message); }
    private void connect(Context context) {
        host = context;
        if (bound) return;
        bound = true;
        bridgeIo.execute(() -> {
            try {
                Intent intent = new Intent().setComponent(new ComponentName(modulePackage,
                    "one.njk.sao.floating.nativebridge.NativeBridgeService"));
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) { bound = false; warn("Bridge unavailable"); }
        });
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bridgeIo.execute(() -> {
                try { INativeBridge.Stub.asInterface(service).registerClient(host.getPackageName(), client); }
                catch (Exception error) { bound = false; warn("Bridge registration rejected"); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { bound = false; }
        @Override public void onBindingDied(ComponentName name) { bound = false; }
    };
    private void scan(View view, Activity current) {
        if (current != activity.get() || !resumed) return;
        if (view instanceof EditText && policy.isComposer((EditText) view)) {
            try { installConnectionHook(view.getClass().getMethod("onCreateInputConnection", EditorInfo.class)); }
            catch (ReflectiveOperationException | RuntimeException error) { editorSession.clear(); }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i), current);
        }
    }
    private void installConnectionHook(Method method) {
        if (!installed.add(method)) return;
        hook(method).intercept(chain -> {
            Object result = chain.proceed();
            EditText actual = (EditText) chain.getThisObject();
            Activity active = activity.get();
            if (active != null && actual.hasFocus() && policy.isComposer(actual) &&
                actual.getRootView() == active.getWindow().getDecorView().getRootView() && result instanceof InputConnection) {
                editorSession.capture(active, actual, (InputConnection) result, (EditorInfo) chain.getArg(0));
                publishState();
            }
            return result;
        });
    }
    private void publishState() {
        if (editorSession != null) cachedState = editorSession.snapshot(activity.get(), resumed);
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
                    if (!expired.get()) accepted[0] = editorSession.insert(activity.get(), resumed, uri, mimeType, expected, requestId);
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
