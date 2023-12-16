package one.njk.sao.database

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity
data class Bookmark(
    @PrimaryKey
    val url: String
)

@Database(entities = [Bookmark::class], version = 1, exportSchema = false)
abstract class SaoDatabase: RoomDatabase(){
    abstract val bookmarksDao: BookmarksDao
}
// TODO: Save category too (with relation & FK)

// TODO: what to do on migrations when schema changes
// TODO: need a simple domain model to have a favourite list
// TODO: need a complex Bookmark UI https://github.com/nijuyonkadesu/SAO/issues/20 hefty extension