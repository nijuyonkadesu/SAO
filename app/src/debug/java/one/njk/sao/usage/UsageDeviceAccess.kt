package one.njk.sao.usage

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Available to local instrumentation only; never packaged into release APKs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UsageDeviceAccess {
    fun usage(): UsageRepository
    fun usageDatabase(): UsageDatabase
    fun usageSession(): UsageSession
}
