package one.njk.sao.floating.nativebridge;
import one.njk.sao.discordprobe.ProbeActivity;
import one.njk.sao.discordprobe.ProbeProvider;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class NativeDiscordProbe extends Instrumentation {
    private long awaitBootstrap;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        try { awaitBootstrap=Long.parseLong(args.getString("await_bootstrap_ms","0")); } catch(NumberFormatException ignored) { }
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONObject report = new JSONObject();
        try {
            // Starting instrumentation force-stops this recipient and revokes prior grants.
            // For the separate-UID permission test, let SAO grant after this process starts.
            android.os.SystemClock.sleep(Math.max(0,Math.min(awaitBootstrap,10_000)));
            Context own = getTargetContext();
            java.util.concurrent.CountDownLatch bound = new java.util.concurrent.CountDownLatch(1);
            boolean[] connected = {false};
            android.content.ServiceConnection connection = new android.content.ServiceConnection() {
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) { connected[0]=true; bound.countDown(); }
                public void onServiceDisconnected(android.content.ComponentName name) {}
            };
            boolean visible;
            try { own.getPackageManager().getPackageInfo("one.njk.sao",0); visible=true; } catch(android.content.pm.PackageManager.NameNotFoundException e) { visible=false; }
            boolean binding=own.bindService(new Intent().setComponent(new android.content.ComponentName("one.njk.sao","one.njk.sao.floating.nativebridge.NativeBridgeService")),connection,Context.BIND_AUTO_CREATE);
            bound.await(2,java.util.concurrent.TimeUnit.SECONDS);
            report.put("sao_visible_without_queries",visible).put("sao_bind_without_queries",binding).put("sao_connected_without_queries",connected[0]);
            if(binding)own.unbindService(connection);
            if(visible) {
                try(var stream=own.getContentResolver().openInputStream(Uri.parse("content://one.njk.sao.fileprovider/native_bridge/ready"))) {
                    report.put("bootstrap_file_empty",stream.read()==-1);
                } catch(SecurityException absentGrant) { report.put("bootstrap_file_empty",false); }
                boolean mediaBlocked=false;
                try { own.getContentResolver().openInputStream(Uri.parse("content://one.njk.sao.fileprovider/floating_exports/ungranted.jpg")); }
                catch(SecurityException expected) {mediaBlocked=true;}
                report.put("ungranted_media_blocked",mediaBlocked);
            }

            for (String name : ProbeProvider.TYPES.keySet()) {
                try (var stream = own.getAssets().open(name)) { Files.copy(stream, new File(own.getFilesDir(), name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
            Activity activity = startActivitySync(new Intent(own,ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                try {
                    Context discord = activity.createPackageContext("com.discord", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    ClassLoader loader = discord.getClassLoader();
                    int theme = discord.getApplicationInfo().theme;
                    Context themed = new ContextThemeWrapper(discord, theme);
                    // Populate RN's font cache using Discord's bundled font. No media code is changed.
                    Class<?> fontType = loader.loadClass("com.discord.fonts.DiscordFont");
                    Object medium = fontType.getField("PrimaryMedium").get(null);
                    String family = (String)fontType.getMethod("getRawName").invoke(medium);
                    Class<?> fontManagerType = loader.loadClass("com.facebook.react.common.assets.ReactFontManager");
                    Object fontManager = fontManagerType.getMethod("getInstance").invoke(null);
                    android.graphics.Typeface typeface = android.graphics.Typeface.createFromAsset(themed.getAssets(),"fonts/ggsans-Medium.ttf");
                    fontManagerType.getMethod("addCustomFont",String.class,android.graphics.Typeface.class).invoke(fontManager,family,typeface);
                    Class<?> rootType = loader.loadClass("com.discord.chat.input.views.ChatInputRootView");
                    Object root = rootType.getConstructor(Context.class).newInstance(themed);
                    Class<?> listenerType = loader.loadClass("com.discord.chat.input.views.ChatInputRootView$ChatInputListener");
                    List<String> inserted = new ArrayList<>();
                    java.util.concurrent.atomic.AtomicInteger sends=new java.util.concurrent.atomic.AtomicInteger();
                    Object listener = Proxy.newProxyInstance(loader,new Class<?>[]{listenerType},(proxy,method,args)->{
                        if (method.getName().equals("onImageInserted")) inserted.add(args[0].toString());
                        if (method.getName().equals("onRequestSend")) sends.incrementAndGet();
                        return null;
                    });
                    rootType.getMethod("setListener",listenerType).invoke(root,listener);
                    var field = rootType.getDeclaredField("editText"); field.setAccessible(true);
                    EditText editor = (EditText)field.get(root);
                    DiscordComposerPolicy policy=new DiscordComposerPolicy(loader);
                    String identity=policy.identity(activity,editor);
                    report.put("exact_native_editor_verified",policy.isComposer(editor)).put("draft_identity_verified",identity!=null);
                    editor.setText("synthetic draft");
                    report.put("draft_revision_changed",identity!=null&&!identity.equals(policy.identity(activity,editor)));
                    android.view.inputmethod.EditorInfo info=new android.view.inputmethod.EditorInfo();
                    android.view.inputmethod.InputConnection ic=editor.onCreateInputConnection(info);
                    report.put("native_allowed_mime_types",new JSONArray(policy.mimeTypes(info)));
                    JSONArray commits=new JSONArray();
                    for(String name:List.of("still.png","motion.gif")) {
                        inserted.clear(); Uri uri=Uri.parse("content://one.njk.sao.discordprobe.fixtures/"+name);
                        boolean accepted=ic.commitContent(new android.view.inputmethod.InputContentInfo(uri,
                            new android.content.ClipDescription("fixture",new String[]{ProbeProvider.TYPES.get(name)}),null),0,null);
                        commits.put(new JSONObject().put("file",name).put("accepted",accepted).put("native_image_inserted",inserted.contains(uri.toString())));
                    }
                    report.put("native_commits",commits).put("send_callbacks",sends.get());
                    report.put("view_receive_mime_types",new JSONArray(policy.viewMimeTypes(editor)));
                    JSONArray receives=new JSONArray();
                    for(String name:List.of("still.png","motion.gif")) {
                        editor.clearFocus(); inserted.clear();
                        Uri uri=Uri.parse("content://one.njk.sao.discordprobe.fixtures/"+name);
                        boolean accepted=policy.insertContent(editor,null,uri,ProbeProvider.TYPES.get(name));
                        receives.put(new JSONObject().put("file",name).put("accepted_without_input_connection",accepted)
                            .put("native_image_inserted",inserted.contains(uri.toString())));
                    }
                    report.put("native_receive_content",receives).put("send_callbacks",sends.get());
                    rootType.getMethod("setListener",listenerType).invoke(root, new Object[]{null});
                    report.put("missing_listener_rejected",policy.identity(activity,editor)==null);
                    rootType.getMethod("setListener",listenerType).invoke(root,listener);
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    JSONArray cases = new JSONArray();
                    for (String name : List.of("still.png","motion.gif","motion.webm","motion.mp4")) {
                        Uri uri = Uri.parse("content://one.njk.sao.discordprobe.fixtures/"+name);
                        clipboard.setPrimaryClip(ClipData.newUri(activity.getContentResolver(),name,uri));
                        editor.setText(""); inserted.clear(); editor.requestFocus();
                        boolean consumed = editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE,null);
                        JSONObject item = new JSONObject();
                        item.put("file",name); item.put("mime",ProbeProvider.TYPES.get(name));
                        item.put("action_consumed",consumed); item.put("native_image_inserted",inserted.contains(uri.toString()));
                        item.put("editor_text",editor.getText().toString()); cases.put(item);
                    }
                    report.put("cases",cases); report.put("native_class_loaded_from_installed_discord",true);
                    report.put("discord_version",activity.getPackageManager().getPackageInfo("com.discord",0).versionName);
                    Class<?> shareType = loader.loadClass("com.discord.share.ShareProps");
                    Object companion = null;
                    for (var member : shareType.getFields()) {
                        if (java.lang.reflect.Modifier.isStatic(member.getModifiers()) && member.getType().getName().equals(shareType.getName()+"$Companion")) companion = member.get(null);
                    }
                    if (companion == null) throw new IllegalStateException("ShareProps companion not found");
                    Intent share = new Intent(Intent.ACTION_SEND).setType("image/gif")
                        .putExtra(Intent.EXTRA_STREAM,Uri.parse("content://one.njk.sao.discordprobe.fixtures/motion.gif"))
                        .putExtra(Intent.EXTRA_SHORTCUT_ID,"123456789012345678");
                    Object props = companion.getClass().getMethod("createShareProps",Intent.class,Context.class).invoke(companion,share,activity);
                    report.put("share_props_target_channel_id",shareType.getMethod("getTargetChannelId").invoke(props));
                    report.put("share_props_attachment_count",((List<?>)shareType.getMethod("getAttachments").invoke(props)).size());
                    report.put("probe_scope","Unmodified Discord native composer loaded in probe UID; React/JS upload and authenticated chat not exercised.");
                } catch (Throwable e) {
                    Throwable cause=e; while(cause.getCause()!=null)cause=cause.getCause();
                    try { report.put("error",cause.toString()); report.put("outer_error",e.toString()); } catch(Exception ignored){}
                }
            });
            activity.finish();
            Files.write(new File(own.getFilesDir(),"report.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            result.putString("report",report.toString()); finish(report.has("error")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        } catch (Throwable e) { result.putString("error",e.toString()); finish(Activity.RESULT_CANCELED,result); }
    }
}
