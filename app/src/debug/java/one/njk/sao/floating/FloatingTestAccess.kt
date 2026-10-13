package one.njk.sao.floating

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FloatingTestAccess {
    fun browseSession(): PickerBrowseSession
    fun sources(): SourceProfiles
    fun mediaSource(): MediaSource
}
