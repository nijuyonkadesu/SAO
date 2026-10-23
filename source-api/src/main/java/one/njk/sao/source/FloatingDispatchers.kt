package one.njk.sao.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/** Limited workers bound platform/CPU concurrency; Mutexes serialize suspending transactions. */
@Singleton
class FloatingDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val cpu: CoroutineDispatcher,
    val conversion: CoroutineDispatcher,
    val assistance: CoroutineDispatcher,
    val clipboard: CoroutineDispatcher,
) {
    @Inject constructor() : this(Dispatchers.Main.immediate, Dispatchers.IO,
        Dispatchers.Default.limitedParallelism(2), Dispatchers.Default.limitedParallelism(1),
        Dispatchers.IO.limitedParallelism(1), Dispatchers.IO.limitedParallelism(1))
}
