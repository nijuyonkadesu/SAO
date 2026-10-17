package one.njk.sao.floating

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.source.*
import one.njk.sao.immichprovider.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Provider

/** Only synthetic/explicitly opted-in test credentials; never part of the host APK. */
private val connections = ConcurrentHashMap<String, ImmichConfig>()
internal suspend fun immichProfile(name: String, config: ImmichConfig, id: String = UUID.randomUUID().toString()): SourceProfile {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val extension = testExtensions(app).installed().single { "immich" in it.legacyKinds }
    val value = ExtensionClient(app,extension.component).request(id,ExtensionProtocol.IMPORT,JSONObject().put("name",name)
        .put("configuration",JSONObject().put("url",config.serverUrl).put("key",config.apiKey)
            .put("smart",config.smartSearch).put("album",config.albumId.orEmpty())))
    val result = extension.profile(SourceWire.profile(value))
    connections[(result.configuration as ExtensionConfig).profile] = config
    return result
}
internal fun testExtensions(context: Context) = EntryPointAccessors.fromApplication(context,SourceExtensionAccess::class.java).extensions()
internal fun SourceProfile.testConnection() = connections.getValue((configuration as ExtensionConfig).profile)
internal suspend fun SourceProfile.changeConnection(config: ImmichConfig) = immichProfile(name,config,(configuration as ExtensionConfig).profile)
internal class ImmichTestSettings(val profiles: SourceProfiles) {
    suspend fun current(): ImmichConfig? = profiles.current().let { it.selected ?: it.profiles.firstOrNull() }
        ?.let { (it.configuration as? ExtensionConfig)?.profile }?.let { connections[it] }
    suspend fun save(config: ImmichConfig?, enabled: Boolean = true) {
        if (config == null) { profiles.current().profiles.forEach { profiles.remove(it.id) }; return }
        val previous = profiles.current().profiles.firstOrNull { connections.containsKey((it.configuration as? ExtensionConfig)?.profile ?: "") }
        val profile = immichProfile(previous?.name ?: "Immich",config,(previous?.configuration as? ExtensionConfig)?.profile ?: UUID.randomUUID().toString())
        profiles.upsert(profile,enabled)
        if (!enabled) profiles.select(null)
    }
}
internal fun FloatingTestAccess.immichSettings() = ImmichTestSettings(sources())
internal fun SwitchingMediaSource(context: Context, local: LocalMediaSource, settings: ImmichTestSettings,
    dispatchers: FloatingDispatchers) = SwitchingMediaSource(local,settings.profiles,
    mapOf("extension" to Provider<MediaSourceFactory> { ExtensionSourceFactory(context,testExtensions(context),SourceMediaCache(context)) }),dispatchers)
internal suspend fun SourceProfiles.restore(catalog: SourceCatalog) {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val retained = catalog.profiles.mapNotNull { (it.configuration as? ExtensionConfig)?.profile }.toSet()
    for (extension in testExtensions(app).installed().filter { "immich" in it.legacyKinds }) {
        val client = ExtensionClient(app,extension.component)
        for (row in client.profiles()) {
            if(connections.containsKey(row.id) && row.id !in retained) {
                client.request(row.id,ExtensionProtocol.DELETE_PROFILE,JSONObject().put("revision",row.revision))
                connections.remove(row.id)
            }
        }
    }
    current().profiles.forEach { remove(it.id) }
    catalog.profiles.forEach { upsert(it) }; select(catalog.selectedId)
}

/** Cross-process controls: the real extension Activity is launched for result by SAO's proxy. */
internal class ExtensionForm(profile: SourceProfile? = null) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val extension = runBlocking { testExtensions(app).installed().single { "immich" in it.legacyKinds } }
    private val scenario = ActivityScenario.launch<SourceSetupActivity>(SourceSetupActivity.intent(app,extension,(profile?.configuration as? ExtensionConfig)?.profile))
    init {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }
    private fun node(id: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("${extension.component.packageName}:id/$id")?.firstOrNull()
    fun await(id: String, predicate: (AccessibilityNodeInfo) -> Boolean = { it.isVisibleToUser }) {
        val until = SystemClock.elapsedRealtime()+10_000
        while (SystemClock.elapsedRealtime()<until) { if(node(id)?.let(predicate)==true) return; SystemClock.sleep(40) }
        throw AssertionError("Extension control did not become ready: $id")
    }
    fun text(id: String): String { await(id); return node(id)?.text?.toString().orEmpty() }
    fun set(id: String,value: String) {
        await(id) { it.isEnabled }; check(node(id)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }))
    }
    fun click(id: String) { await(id) { it.isEnabled }; check(node(id)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    override fun close() {
        if (node("back") != null) click("back")
        scenario.close()
    }
}

internal fun pauseSourceVisual(stage: String, details: String = "") {
    if(InstrumentationRegistry.getArguments().getString("visualPause")!="true") return
    val app=InstrumentationRegistry.getInstrumentation().targetContext
    val resume=java.io.File(app.cacheDir,"source-visual-continue").apply { delete() }
    val ready=java.io.File(app.cacheDir,"source-visual-$stage").apply { writeText(details) }
    try {
        val deadline=SystemClock.elapsedRealtime()+50_000
        while(!resume.exists() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
        check(resume.exists()) { "Visual inspection did not resume: $stage" }
    } finally { ready.delete(); resume.delete() }
}
