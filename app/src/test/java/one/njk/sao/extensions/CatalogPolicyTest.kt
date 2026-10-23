package one.njk.sao.extensions

import org.junit.Assert.*
import org.junit.Test

class CatalogPolicyTest {
    private val entry=CatalogExtension("local","one.njk.sao.extension.local","Local","Local source",10,"1.10",1,30,"publisher","stable",
        listOf(ExtensionArtifact("arm64-v8a","url","hash",1),ExtensionArtifact("x86_64","other","hash",1)))
    @Test fun updatesUseNumericVersionsAndNeverOfferDowngrades() {
        assertEquals(CatalogAction.UPDATE,entry.action(9,"publisher",35,listOf("arm64-v8a")))
        assertEquals(CatalogAction.CURRENT,entry.action(10,"publisher",35,listOf("arm64-v8a")))
        assertEquals(CatalogAction.NEWER,entry.action(11,"publisher",35,listOf("arm64-v8a")))
    }
    @Test fun updatesDoNotReplaceAnApprovedIndependentPublisher() {
        assertEquals(CatalogAction.DIFFERENT_PUBLISHER,entry.action(1,"another-publisher",35,listOf("arm64-v8a")))
    }
    @Test fun protocolDeviceAndArchitectureGateInstallation() {
        assertEquals(CatalogAction.INSTALL,entry.action(null,null,35,listOf("x86_64")))
        assertEquals(CatalogAction.INCOMPATIBLE,entry.action(null,null,29,listOf("x86_64")))
        assertEquals(CatalogAction.INCOMPATIBLE,entry.action(null,null,35,listOf("armeabi-v7a")))
        assertEquals(CatalogAction.INCOMPATIBLE,entry.copy(apiVersion=2).action(null,null,35,listOf("x86_64")))
    }
    @Test fun nativeArtifactFollowsDevicePreferenceAndUniversalIsAFallback() {
        val mixed=entry.copy(artifacts=entry.artifacts+ExtensionArtifact("universal","all","hash",1))
        assertEquals("x86_64",mixed.artifact(listOf("x86_64","arm64-v8a"))?.abi)
        assertEquals("universal",mixed.artifact(listOf("armeabi-v7a"))?.abi)
    }
}
