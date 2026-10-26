package one.njk.sao.usage

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import dagger.hilt.android.EntryPointAccessors
import one.njk.sao.source.FloatingDispatchers
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class UsageStorageDeviceTest {
    @Test fun controlsAndSameWeekDateChangesReusePreparedReports(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val db=Room.inMemoryDatabaseBuilder(context,UsageDatabase::class.java).build()
        val repository=UsageRepository(db,FloatingDispatchers())
        val store=ViewModelStore()
        lateinit var model: UsageViewModel
        instrumentation.runOnMainSync {
            model=UsageViewModel(repository,SavedStateHandle(),
                EntryPointAccessors.fromApplication(context,one.njk.sao.floating.FloatingTestAccess::class.java).sources(),FloatingDispatchers())
            store.put("usage",model)
        }
        val observer=launch(Dispatchers.Main.immediate) { model.state.collect {} }
        try {
            val first=withTimeout(5000) { model.state.first { it.report!=null } }.report
            repository.enabled(false)
            assertSame(first,withTimeout(5000) { model.state.first { it.enabled==false } }.report)
            repository.enabled(true)
            assertSame(first,withTimeout(5000) { model.state.first { it.enabled==true } }.report)
            val date=UsageSelection(UsageRange.WEEK,LocalDate.now().minusYears(1),model.selectionState.value.firstWeekday).start
            db.usage().record(UsageBucket(date.toEpochDay(),9,UsageAction.SEARCH,UsageSurface.PICKER,occurrences=2))
            db.usage().record(UsageBucket(date.plusDays(1).toEpochDay(),11,UsageAction.COPY_MEDIA,UsageSurface.PICKER,occurrences=3))
            instrumentation.runOnMainSync { model.date(date) }
            val before=withTimeout(5000) { model.state.first { it.report?.selection?.anchor==date && it.report.day.actions==2L } }.report!!
            instrumentation.runOnMainSync { model.date(date.plusDays(1)) }
            val after=withTimeout(5000) { model.state.first { it.report?.selection?.anchor==date.plusDays(1) } }.report!!
            assertSame(before.calendarMonths,after.calendarMonths)
            assertSame(before.week.hourly,after.week.hourly)
            assertSame(before.year.daily,after.year.daily)
            assertEquals(3L,after.day.actions)
        } finally {
            observer.cancelAndJoin(); instrumentation.runOnMainSync { store.clear() }
            repository.close(); db.close()
        }
    }
    @Test fun migrationPreservesHistoryAndDailyCountersStayAtomic(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="usage-migration-fixture.db"
        context.deleteDatabase(name)
        val legacy=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object: SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""CREATE TABLE usage_bucket (day INTEGER NOT NULL,hour INTEGER NOT NULL,action TEXT NOT NULL,
                        surface TEXT NOT NULL,sourceId TEXT NOT NULL,media TEXT NOT NULL,target TEXT NOT NULL,route TEXT NOT NULL,
                        outcome TEXT NOT NULL,searchMode TEXT NOT NULL,occurrences INTEGER NOT NULL,results INTEGER NOT NULL,latencyMillis INTEGER NOT NULL,
                        PRIMARY KEY(day,hour,action,surface,sourceId,media,target,route,outcome,searchMode))""")
                    db.execSQL("CREATE TABLE usage_control (id INTEGER NOT NULL PRIMARY KEY,enabled INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO usage_control VALUES(1,0)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase,oldVersion: Int,newVersion: Int)=Unit
            }).build())
        val rows=UsageAction.entries.flatMap { action -> UsageOutcome.entries.map { outcome ->
            UsageBucket(20000,action.ordinal,action,UsageSurface.PICKER,outcome=outcome,occurrences=2,results=3,latencyMillis=5)
        } }
        try {
            rows.forEach { row -> legacy.writableDatabase.execSQL("INSERT INTO usage_bucket VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(row.day,row.hour,row.action.name,row.surface.name,row.sourceId,row.media.name,row.target.name,row.route.name,
                    row.outcome.name,row.searchMode.name,row.occurrences,row.results,row.latencyMillis)) }
        } finally { legacy.close() }
        val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).addMigrations(UsageDatabase.MIGRATION_1_2).build()
        try {
            val dao=db.usage()
            assertEquals(listOf(UsageDay(20000,48,6,2,6)),dao.observeDays().first())
            assertFalse(dao.control()!!.enabled)
            val migrated=dao.observe(20000,20001).first()
            assertEquals(rows.size,migrated.size); assertEquals(120L,migrated.sumOf { it.latencyMillis })
            rows.forEach { dao.record(it) }
            assertEquals(listOf(UsageDay(20000,96,12,4,12)),dao.observeDays().first())
            assertEquals(96L,dao.observe(20000,20001).first().sumOf { it.occurrences })
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_daily BEFORE UPDATE ON usage_day BEGIN SELECT RAISE(ABORT,'fixture'); END")
            try { dao.record(rows.first()); fail("Daily and hourly counters must roll back together") }
            catch(_: SQLiteException) { /* Intentional fixture failure. */ }
            assertEquals(96L,dao.observeDays().first().single().actions)
            assertEquals(96L,dao.observe(20000,20001).first().sumOf { it.occurrences })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_daily")
            dao.clear()
            assertTrue(dao.observeDays().first().isEmpty()); assertTrue(dao.observe(20000,20001).first().isEmpty())
            assertFalse(dao.control()!!.enabled)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
