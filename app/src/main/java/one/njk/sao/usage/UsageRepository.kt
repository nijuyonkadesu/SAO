package one.njk.sao.usage

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import one.njk.sao.source.FloatingDispatchers
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

interface UsageRecorder { fun record(event: UsageEvent) }
object NoUsageRecorder: UsageRecorder { override fun record(event: UsageEvent) = Unit }

/** One bounded writer. Observing graphs never binds an extension or starts a timer. */
@Singleton
class UsageRepository @Inject constructor(database: UsageDatabase,dispatchers: FloatingDispatchers): UsageRecorder {
    private val dao=database.usage()
    private val scope=CoroutineScope(SupervisorJob()+dispatchers.io)
    private sealed interface Command {
        data class Record(val bucket: UsageBucket): Command
        data class Enable(val value: Boolean,val done: CompletableDeferred<Unit>): Command
        data class Clear(val done: CompletableDeferred<Unit>): Command
        data class Flush(val done: CompletableDeferred<Unit>): Command
    }
    private val commands=Channel<Command>(128)
    private val failed=MutableStateFlow(false)
    val failure=failed.asStateFlow()
    val enabled=dao.observeControl().map { it?.enabled ?: true }.distinctUntilChanged()
    val days=dao.observeDays().distinctUntilChanged()
    fun buckets(selection: UsageSelection)=dao.observe(selection.start.toEpochDay(),selection.endExclusive.toEpochDay()).distinctUntilChanged()
    private val writer by lazy { scope.launch {
        var collecting: Boolean?=null
        for(command in commands) {
            try {
                when(command) {
                    is Command.Record -> {
                        val active=collecting ?: (dao.control()?.enabled ?: true).also { collecting=it }
                        if(active) dao.record(command.bucket)
                    }
                    is Command.Enable -> { dao.control(UsageControl(enabled=command.value)); collecting=command.value; failed.value=false; command.done.complete(Unit) }
                    is Command.Clear -> { dao.clear(); failed.value=false; command.done.complete(Unit) }
                    is Command.Flush -> command.done.complete(Unit)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                failed.value=true
                when(command) {
                    is Command.Enable -> command.done.completeExceptionally(error)
                    is Command.Clear -> command.done.completeExceptionally(error)
                    is Command.Flush -> command.done.completeExceptionally(error)
                    is Command.Record -> Unit // Optional statistics cannot fail a media action.
                }
            }
        }
    } }
    override fun record(event: UsageEvent) {
        if(event.action==UsageAction.SEARCH && UsageEvent.profileId(event.sourceId).isEmpty()) return
        writer
        if(!commands.trySend(Command.Record(event.bucket(LocalDateTime.now()))).isSuccess) failed.value=true
    }
    suspend fun enabled(value: Boolean) { writer; val done=CompletableDeferred<Unit>(); commands.send(Command.Enable(value,done)); done.await() }
    suspend fun clear() { writer; val done=CompletableDeferred<Unit>(); commands.send(Command.Clear(done)); done.await() }
    internal suspend fun flush() { writer; val done=CompletableDeferred<Unit>(); commands.send(Command.Flush(done)); done.await() }
    internal suspend fun close() { commands.close(); if(writer.isActive) writer.join(); scope.cancel() }
}

@Module
@InstallIn(SingletonComponent::class)
object UsageModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): UsageDatabase =
        Room.databaseBuilder(context,UsageDatabase::class.java,"usage.db").addMigrations(UsageDatabase.MIGRATION_1_2).build()
    @Provides fun recorder(repository: UsageRepository): UsageRecorder = repository
}
