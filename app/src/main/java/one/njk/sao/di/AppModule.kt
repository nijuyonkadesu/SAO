package one.njk.sao.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import one.njk.sao.data.WaifuApiRepository
import one.njk.sao.data.WaifuApiRepositoryImpl
import one.njk.sao.database.SaoDatabase
import one.njk.sao.network.WaifuApiService
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

private const val BASE_URL = "https://api.waifu.pics"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWaifuApi(): WaifuApiService {
        // extract JSON -> Kotlin data class
        val moshi = Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        // retrofit returns json response: String
        // to work with JSON, need MoshiConverterFactory -> which extracts JSON
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        return retrofit.create(WaifuApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        // customizing add GIF Decoder
        val imageLoader = ImageLoader.Builder(context)
            .components {
                add(ImageDecoderDecoder.Factory())
            }.build()

        return imageLoader
    }

    @Provides
    @Singleton
    fun provideWaifuRepository(api: WaifuApiService): WaifuApiRepository {
        return WaifuApiRepositoryImpl(api)
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