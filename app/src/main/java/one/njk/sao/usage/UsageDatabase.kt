package one.njk.sao.usage

import androidx.room.*
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

@Dao
abstract class UsageDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(bucket: UsageBucket): Long

    @Query("""UPDATE usage_bucket SET occurrences=occurrences+:count, results=results+:results, latencyMillis=latencyMillis+:latency
        WHERE day=:day AND hour=:hour AND action=:action AND surface=:surface AND sourceId=:sourceId
        AND media=:media AND target=:target AND route=:route AND outcome=:outcome AND searchMode=:mode""")
    protected abstract suspend fun increment(day: Long,hour: Int,action: UsageAction,surface: UsageSurface,sourceId: String,
        media: UsageMedia,target: UsageTarget,route: UsageRoute,outcome: UsageOutcome,mode: UsageSearchMode,count: Long,results: Long,latency: Long)

    @Transaction
    open suspend fun record(bucket: UsageBucket) {
        if(insert(bucket)==-1L) bucket.let { increment(it.day,it.hour,it.action,it.surface,it.sourceId,it.media,it.target,it.route,
            it.outcome,it.searchMode,it.occurrences,it.results,it.latencyMillis) }
    }

    @Query("SELECT * FROM usage_bucket WHERE day>=:from AND day<:until ORDER BY day,hour")
    abstract fun observe(from: Long,until: Long): Flow<List<UsageBucket>>

    @Query("""SELECT day, SUM(occurrences) AS actions,
        SUM(CASE WHEN action='SEARCH' THEN occurrences ELSE 0 END) AS searches,
        SUM(CASE WHEN action='COPY_MEDIA' AND outcome='ACCEPTED' THEN occurrences ELSE 0 END) AS copies,
        SUM(CASE WHEN action IN ('PASTE','SHARE','STICKER') AND outcome='ACCEPTED' THEN occurrences ELSE 0 END) AS handoffs
        FROM usage_bucket GROUP BY day ORDER BY day""")
    abstract fun observeDays(): Flow<List<UsageDay>>

    @Query("SELECT * FROM usage_control WHERE id=1") abstract suspend fun control(): UsageControl?
    @Query("SELECT * FROM usage_control WHERE id=1") abstract fun observeControl(): Flow<UsageControl?>
    @Upsert abstract suspend fun control(value: UsageControl)
    @Query("DELETE FROM usage_bucket") abstract suspend fun clear()
}

@Database(entities=[UsageBucket::class,UsageControl::class],version=1,exportSchema=false)
abstract class UsageDatabase: RoomDatabase() { abstract fun usage(): UsageDao }
