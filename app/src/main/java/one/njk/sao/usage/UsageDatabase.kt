package one.njk.sao.usage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="usage_bucket",primaryKeys=["day","hour","action","surface","sourceId","media","target","route","outcome","searchMode"])
data class UsageBucket(
    val day: Long,
    val hour: Int,
    val action: UsageAction,
    val surface: UsageSurface,
    val sourceId: String = "",
    val media: UsageMedia = UsageMedia.NONE,
    val target: UsageTarget = UsageTarget.NONE,
    val route: UsageRoute = UsageRoute.NONE,
    val outcome: UsageOutcome = UsageOutcome.ACCEPTED,
    val searchMode: UsageSearchMode = UsageSearchMode.NONE,
    val occurrences: Long = 1,
    val results: Long = 0,
    val latencyMillis: Long = 0,
)

@Entity(tableName="usage_control")
data class UsageControl(@PrimaryKey val id: Int = 1,val enabled: Boolean)

@Entity(tableName="usage_day")
data class UsageDay(@PrimaryKey val day: Long,val actions: Long,val searches: Long,val copies: Long,val handoffs: Long)

internal fun UsageBucket.dayDelta(): UsageDay {
    val accepted=outcome==UsageOutcome.ACCEPTED
    return UsageDay(day,occurrences,if(action==UsageAction.SEARCH) occurrences else 0,
        if(action==UsageAction.COPY_MEDIA && accepted) occurrences else 0,
        if(action.isHandoff && accepted) occurrences else 0)
}

@Dao
abstract class UsageDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(bucket: UsageBucket): Long

    @Query("""UPDATE usage_bucket SET occurrences=occurrences+:count, results=results+:results, latencyMillis=latencyMillis+:latency
        WHERE day=:day AND hour=:hour AND action=:action AND surface=:surface AND sourceId=:sourceId
        AND media=:media AND target=:target AND route=:route AND outcome=:outcome AND searchMode=:mode""")
    protected abstract suspend fun increment(day: Long,hour: Int,action: UsageAction,surface: UsageSurface,sourceId: String,
        media: UsageMedia,target: UsageTarget,route: UsageRoute,outcome: UsageOutcome,mode: UsageSearchMode,count: Long,results: Long,latency: Long)

    @Insert(onConflict=OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDay(day: UsageDay)

    @Query("""UPDATE usage_day SET actions=actions+:actions, searches=searches+:searches,
        copies=copies+:copies, handoffs=handoffs+:handoffs WHERE day=:day""")
    protected abstract suspend fun incrementDay(day: Long,actions: Long,searches: Long,copies: Long,handoffs: Long): Int

    @Transaction
    open suspend fun record(bucket: UsageBucket) {
        if(insert(bucket)==-1L) bucket.let { increment(it.day,it.hour,it.action,it.surface,it.sourceId,it.media,it.target,it.route,
            it.outcome,it.searchMode,it.occurrences,it.results,it.latencyMillis) }
        val delta=bucket.dayDelta()
        if(incrementDay(delta.day,delta.actions,delta.searches,delta.copies,delta.handoffs)==0) insertDay(delta)
    }

    @Query("SELECT * FROM usage_bucket WHERE day>=:from AND day<:until ORDER BY day,hour")
    abstract fun observe(from: Long,until: Long): Flow<List<UsageBucket>>

    @Query("SELECT * FROM usage_day ORDER BY day")
    abstract fun observeDays(): Flow<List<UsageDay>>

    @Query("SELECT * FROM usage_control WHERE id=1") abstract suspend fun control(): UsageControl?
    @Query("SELECT * FROM usage_control WHERE id=1") abstract fun observeControl(): Flow<UsageControl?>
    @Upsert abstract suspend fun control(value: UsageControl)
    @Query("DELETE FROM usage_bucket") protected abstract suspend fun clearBuckets()
    @Query("DELETE FROM usage_day") protected abstract suspend fun clearDays()
    @Transaction open suspend fun clear() { clearBuckets(); clearDays() }
}

@Database(entities=[UsageBucket::class,UsageControl::class,UsageDay::class],version=2,exportSchema=false)
abstract class UsageDatabase: RoomDatabase() {
    abstract fun usage(): UsageDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS usage_day (day INTEGER NOT NULL PRIMARY KEY,
                    actions INTEGER NOT NULL, searches INTEGER NOT NULL, copies INTEGER NOT NULL, handoffs INTEGER NOT NULL)""")
                db.execSQL("""INSERT INTO usage_day (day,actions,searches,copies,handoffs)
                    SELECT day, SUM(occurrences), SUM(CASE WHEN action='SEARCH' THEN occurrences ELSE 0 END),
                    SUM(CASE WHEN action='COPY_MEDIA' AND outcome='ACCEPTED' THEN occurrences ELSE 0 END),
                    SUM(CASE WHEN action IN ('PASTE','SHARE','STICKER') AND outcome='ACCEPTED' THEN occurrences ELSE 0 END)
                    FROM usage_bucket GROUP BY day""")
            }
        }
    }
}
