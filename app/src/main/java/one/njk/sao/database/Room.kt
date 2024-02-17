package one.njk.sao.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BookmarksDao {
    @Query("select * from Bookmark")
    fun get(): List<Bookmark>
    @Upsert
    fun save(bookmark: Bookmark)
    @Query("delete from Bookmark")
    fun invalidateCache()
}