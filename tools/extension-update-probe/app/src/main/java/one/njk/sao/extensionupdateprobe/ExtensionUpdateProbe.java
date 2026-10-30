package one.njk.sao.extensionupdateprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Isolated Java-only UI journey; does not load classes from the minified host. */
public final class ExtensionUpdateProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result=new ArrayList<>();
        if(root!=null) { result.add(root); for(int i=0;i<root.getChildCount();i++) result.addAll(nodes(root.getChild(i))); }
        return result;
    }
    private static AccessibilityNodeInfo id(List<AccessibilityNodeInfo> nodes,String id) {
        for(AccessibilityNodeInfo node:nodes) if(id.equals(node.getViewIdResourceName())) return node;
        return null;
    }
    private static boolean click(AccessibilityNodeInfo node) {
        while(node!=null && !node.isClickable()) node=node.getParent();
        return node!=null && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private PackageInfo installed(String target) throws Exception {
        try { return getContext().getPackageManager().getPackageInfo(target,0); }
        catch(android.content.pm.PackageManager.NameNotFoundException missing) { return null; }
    }
    @Override public void onStart() {
        JSONObject report=new JSONObject(); Bundle output=new Bundle();
        try {
            if(!"true".equals(arguments.getString("allowPublicUpdate"))) throw new IllegalArgumentException("Explicit allowPublicUpdate=true required on an owned emulator");
            String target=arguments.getString("extensionPackage","one.njk.sao.extension.moeview");
            String name=arguments.getString("extensionName","Moeview");
            long expected=Long.parseLong(arguments.getString("expectedVersion"));
            boolean fresh="install".equals(arguments.getString("operation","update"));
            PackageInfo before=installed(target);
            if(fresh ? before!=null : before==null || before.getLongVersionCode()>=expected)
                throw new IllegalStateException(fresh ? "Fresh-install target must be absent" : "Stage the previous extension release first");
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            Intent launch=getContext().getPackageManager().getLaunchIntentForPackage("one.njk.sao");
            if(launch==null) throw new IllegalStateException("Install the signed host first");
            getContext().startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            boolean entered=false,pressed=false,confirmed=false,permission=false;
            long nextScroll=0,deadline=SystemClock.elapsedRealtime()+120_000;
            while(SystemClock.elapsedRealtime()<deadline) {
                PackageInfo installed=installed(target);
                if(installed!=null && installed.getLongVersionCode()==expected) {
                    if(!confirmed || !fresh && before.firstInstallTime!=installed.firstInstallTime)
                        throw new AssertionError("Installation must exercise confirmation and updates must retain the installation");
                    report.put("success",true).put("freshInstall",fresh).put("previousVersion",fresh ? 0 : before.getLongVersionCode()).put("installedVersion",expected)
                        .put("installerConfirmed",true).put("installationRetained",!fresh).put("permissionPrompt",permission);
                    output.putString("report",report.toString());
                    try(FileOutputStream file=new FileOutputStream(new File(getContext().getFilesDir(),"update-report.json"))) {
                        file.write(report.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    finish(Activity.RESULT_OK,output); return;
                }
                AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                List<AccessibilityNodeInfo> visible=nodes(root);
                String pkg=root==null?"":String.valueOf(root.getPackageName());
                if("com.android.settings".equals(pkg)) {
                    AccessibilityNodeInfo toggle=null;
                    for(AccessibilityNodeInfo node:visible) if(node.isCheckable() && node.isEnabled()) { toggle=node; break; }
                    if(toggle!=null) {
                        if(!toggle.isChecked() && !click(toggle)) throw new AssertionError("Could not grant source installation permission");
                        permission=true;
                        getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    }
                } else if(Set.of("com.google.android.packageinstaller","com.android.packageinstaller").contains(pkg)) {
                    AccessibilityNodeInfo button=id(visible,"android:id/button1");
                    if(button!=null && button.isEnabled() && Set.of("Update","Install").contains(String.valueOf(button.getText()))) {
                        if(!click(button)) throw new AssertionError("Could not confirm Android update");
                        confirmed=true;
                    }
                } else if("one.njk.sao".equals(pkg)) {
                    if(!entered) {
                        AccessibilityNodeInfo edit=id(visible,"one.njk.sao:id/edit");
                        if(edit!=null && click(edit)) entered=true;
                    } else if(!pressed) {
                        AccessibilityNodeInfo update=null;
                        for(AccessibilityNodeInfo node:visible) if(((fresh ? "Install " : "Update ")+name).equals(String.valueOf(node.getContentDescription()))) { update=node; break; }
                        if(update!=null && update.isEnabled()) pressed=click(update);
                        else if(SystemClock.elapsedRealtime()>=nextScroll) {
                            for(AccessibilityNodeInfo node:visible) if(node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
                            nextScroll=SystemClock.elapsedRealtime()+700;
                        }
                    } else for(AccessibilityNodeInfo node:visible) {
                        String text=String.valueOf(node.getText());
                        if("one.njk.sao:id/install_status".equals(node.getViewIdResourceName()) &&
                            (text.startsWith("The download was interrupted") || text.startsWith("Could not check for extensions") || text.startsWith("Could not install")))
                            throw new AssertionError("Update failed: "+text);
                    }
                }
                SystemClock.sleep(150);
            }
            throw new AssertionError("Update journey timed out");
        } catch(Exception|AssertionError error) {
            output.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());
            finish(Activity.RESULT_CANCELED,output);
        }
    }
}
