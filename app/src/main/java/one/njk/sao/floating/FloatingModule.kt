package one.njk.sao.floating

import one.njk.sao.source.*

import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.content.Context
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HomeSources

@Module
@InstallIn(SingletonComponent::class)
abstract class FloatingModule {
    companion object {
        @Provides @Singleton
        fun floatingSource(profiles: SourceProfiles, factories: Map<String, @JvmSuppressWildcards Provider<MediaSourceFactory>>,
            dispatchers: FloatingDispatchers): MediaSource = SwitchingMediaSource(profiles, factories, dispatchers, floatingOnly = true)

        @Provides @Singleton @HomeSources
        fun homeSource(profiles: SourceProfiles, factories: Map<String, @JvmSuppressWildcards Provider<MediaSourceFactory>>,
            dispatchers: FloatingDispatchers): MediaSource = SwitchingMediaSource(profiles, factories, dispatchers)

        @Provides @Singleton @HomeSources
        fun homeExports(@ApplicationContext context: Context, @HomeSources source: MediaSource,
            dispatchers: FloatingDispatchers, transcoder: VideoTranscoder, store: MediaExportStore) = MediaExports(context, source, dispatchers, transcoder, store)
    }
    @Binds @IntoMap @StringKey("rays")
    abstract fun raysFactory(factory: RaysSourceFactory): MediaSourceFactory
    @Binds @IntoMap @StringKey("rays")
    abstract fun raysCodec(codec: RaysConfigurationCodec): SourceConfigurationCodec
    @Binds @IntoMap @StringKey("extension")
    abstract fun extensionFactory(factory: ExtensionSourceFactory): MediaSourceFactory
    @Binds @IntoMap @StringKey("extension")
    abstract fun extensionCodec(codec: ExtensionConfigurationCodec): SourceConfigurationCodec
}
