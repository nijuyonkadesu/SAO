package one.njk.sao.floating

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import one.njk.sao.database.SettingsDataStore
import one.njk.sao.source.*
import javax.inject.Inject
import javax.inject.Singleton

enum class ExtensionApproval { PUBLISHER, USER, UNTRUSTED }

/** Only explicit user decisions write preferences. Package names alone never establish trust. */
@Singleton
class ExtensionTrust @Inject constructor(@ApplicationContext private val context: Context,private val settings: SettingsDataStore) {
    suspend fun status(packageName: String,signer: String?=null): ExtensionApproval = withContext(Dispatchers.IO) {
        if(SourceSignatures.samePublisher(context,packageName)) ExtensionApproval.PUBLISHER
        else if("$packageName|${signer ?: SourceSignatures.identity(context,packageName)}" in settings.extensionApprovalsFlow().first()) ExtensionApproval.USER
        else ExtensionApproval.UNTRUSTED
    }
    suspend fun allowed(packageName: String) = try { status(packageName)!=ExtensionApproval.UNTRUSTED } catch(_: android.content.pm.PackageManager.NameNotFoundException) { false }
    suspend fun approve(packageName: String,expectedSigner: String) = withContext(Dispatchers.IO) {
        if(SourceSignatures.identity(context,packageName)!=expectedSigner) throw ExtensionFailure("UNTRUSTED")
        settings.extensionApproval(packageName,expectedSigner)
    }
    suspend fun revoke(packageName: String) = settings.extensionApproval(packageName,null)
    suspend fun caller(uid: Int): String? = withContext(Dispatchers.IO) {
        context.packageManager.getPackagesForUid(uid).orEmpty().firstOrNull { allowed(it) }
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface ExtensionTrustAccess { fun extensionTrust(): ExtensionTrust }

/** A source may ask only about its own approval; no credentials or profile data are exposed. */
class ExtensionTrustProvider : ContentProvider() {
    override fun onCreate()=true
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        if(method!=SourceAccess.AUTHORIZE) throw IllegalArgumentException("Unsupported operation")
        val app=requireNotNull(context)
        val uid=Binder.getCallingUid()
        val owner=uid==android.os.Process.myUid()
        val valid=arg!=null && (owner || arg in app.packageManager.getPackagesForUid(uid).orEmpty())
        val allowed=valid && runBlocking(Dispatchers.IO) {
            EntryPointAccessors.fromApplication(app.applicationContext,ExtensionTrustAccess::class.java).extensionTrust().allowed(requireNotNull(arg))
        }
        return Bundle().apply { putBoolean(SourceAccess.ALLOWED,allowed) }
    }
    override fun getType(uri: Uri): String?=null
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,order: String?): Cursor?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=throw UnsupportedOperationException()
}
