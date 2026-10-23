package one.njk.sao.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import one.njk.sao.database.SaoDatabase
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        // customizing add GIF Decoder
        val imageLoader = ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
            }.build()

        return imageLoader
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context)
            = Room.databaseBuilder(
        context = context,
        SaoDatabase::class.java, "sao"
    ).fallbackToDestructiveMigration().build()

    @Provides
    @Singleton
    fun provideBookmarksDao(db: SaoDatabase) = db.bookmarksDao
}
