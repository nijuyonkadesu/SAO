package one.njk.sao.floating

import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FloatingModule {
    @Binds abstract fun mediaSource(backends: SwitchingMediaSource): MediaSource
    @Binds @IntoMap @StringKey("immich")
    abstract fun immichFactory(factory: ImmichSourceFactory): MediaSourceFactory
    @Binds @IntoMap @StringKey("immich")
    abstract fun immichCodec(codec: ImmichConfigurationCodec): SourceConfigurationCodec
}
