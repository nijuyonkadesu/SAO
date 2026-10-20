package one.njk.sao.extension.moeview

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import one.njk.sao.extension.moeview.databinding.ActivityMoeviewSettingsBinding
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.SourceSettingsGuard

class MoeviewSettingsActivity : one.njk.sao.source.settings.SourceSettingsActivity() {
    private val model by lazy { ViewModelProvider(this)[MoeviewSettingsViewModel::class.java] }
    private val verification=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data=result.data
        if(result.resultCode==RESULT_OK && data?.getStringExtra("board")==model.state.value.config.board.name) model.configure {
            it.copy(clearance=data.getStringExtra("cookies").orEmpty(),browserAgent=data.getStringExtra("agent").orEmpty())
        }
    }
    override fun onSourceCreate(savedInstanceState: Bundle?,caller: String) {
        val binding=ActivityMoeviewSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        var rendering=false
        val guard=SourceSettingsGuard(this,binding.saveConnection,{ model.hasChanges },{ model.state.value.working },model::save)
        binding.back.setOnClickListener { guard.leave() }
        binding.sourceName.doAfterTextChanged { if(!rendering) model.name(it.toString()) }
        binding.baseTags.doAfterTextChanged { if(!rendering) model.configure { c -> c.copy(tags=it.toString()) } }
        binding.account.doAfterTextChanged { if(!rendering) model.configure { c -> c.copy(login=it.toString()) } }
        binding.apiKey.doAfterTextChanged { if(!rendering) model.configure { c -> c.copy(apiKey=it.toString()) } }
        binding.board.setSimpleItems(Board.entries.map { it.label }.toTypedArray())
        binding.board.setOnItemClickListener { _,_,index,_ -> model.configure { it.copy(board=Board.entries[index],login="",apiKey="",clearance="",browserAgent="") } }
        binding.rating.setSimpleItems(arrayOf(getString(R.string.rating_safe),getString(R.string.rating_non_safe),getString(R.string.rating_all)))
        binding.rating.setOnItemClickListener { _,_,index,_ -> model.configure { it.copy(rating=SfwMode.entries[index]) } }
        binding.useProxy.setOnCheckedChangeListener { _,value -> if(!rendering) model.configure { it.copy(proxy=value) } }
        binding.clearVerification.setOnClickListener { model.configure { it.copy(clearance="",browserAgent="") } }
        binding.verifyCloudflare.setOnClickListener { verification.launch(Intent(this,BoardVerificationActivity::class.java).putExtra("board",model.state.value.config.board.name)) }
        binding.testConnection.setOnClickListener { model.check() }; binding.saveConnection.setOnClickListener { model.save() }
        binding.deleteSource.setOnClickListener { model.delete() }; binding.deleteSource.isVisible=model.editing!=null
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            rendering=true
            fun androidx.appcompat.widget.AppCompatEditText.show(value: String) { if(text.toString()!=value) setText(value) }
            binding.sourceName.show(state.name); binding.baseTags.show(state.config.tags)
            binding.account.show(state.config.login); binding.apiKey.show(state.config.apiKey)
            binding.board.setText(state.config.board.label,false)
            binding.rating.setText(getString(when(state.config.rating) { SfwMode.SFW -> R.string.rating_safe; SfwMode.NSFW -> R.string.rating_non_safe; SfwMode.ALL -> R.string.rating_all }),false)
            binding.clearVerification.isVisible=state.config.clearance.isNotBlank()
            binding.accountFields.isVisible=state.config.supportsAuth
            binding.accountBox.hint=getString(if(state.config.board==Board.GELBOORU) R.string.user_id else R.string.username)
            binding.useProxy.isVisible=state.config.board.edgeBase!=null
            binding.proxyHelp.isVisible=binding.useProxy.isVisible
            val proxyAllowed=!state.config.authenticated && state.config.clearance.isEmpty() && !(state.config.board.edgeIsSfwOnly && state.config.rating!=SfwMode.SFW)
            binding.useProxy.isChecked=state.config.proxy && proxyAllowed
            binding.useProxy.isEnabled=state.loaded && !state.working && proxyAllowed
            rendering=false
            listOf(binding.sourceName,binding.baseTags,binding.board,binding.rating,binding.account,binding.apiKey,binding.testConnection,binding.saveConnection,binding.deleteSource,binding.verifyCloudflare,binding.clearVerification)
                .forEach { it.isEnabled=state.loaded && !state.working }
            binding.status.setText(state.status); binding.progress.isVisible=state.working || !state.loaded
            if(state.finished) { setResult(RESULT_OK,Intent().putExtra(ExtensionProtocol.PROFILE,model.profile)); finish() }
        } } }
    }
}
