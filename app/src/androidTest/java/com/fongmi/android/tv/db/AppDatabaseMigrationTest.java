package com.fongmi.android.tv.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {

    private static final String TEST_DB = "migration-v2-test";

    @Rule
    public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(TEST_DB);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(TEST_DB);
    }

    @Test
    public void migration35To36PreservesCoreDataAndCreatesRepositorySchema() throws Exception {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 35);
        insertCoreFixtures(database);
        database.close();

        database = helper.runMigrationsAndValidate(TEST_DB, 36, true, Migrations.MIGRATION_35_36);

        assertCoreFixtures(database);
        assertTrue(tableExists(database, "Repository"));
        assertTrue(tableExists(database, "RepositoryItem"));
        assertTrue(indexExists(database, "index_Repository_stableId"));
        assertTrue(indexExists(database, "index_Repository_priority"));
        assertTrue(indexExists(database, "index_RepositoryItem_repositoryId"));
        assertTrue(indexExists(database, "index_RepositoryItem_repositoryId_url_type"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM Repository"));
        database.close();
    }

    @Test
    public void migration36To37PreservesRepositoryAndCreatesCloudAccount() throws Exception {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 36);
        insertCoreFixtures(database);
        insertRepositoryFixture(database);
        database.close();

        database = helper.runMigrationsAndValidate(TEST_DB, 37, true, Migrations.MIGRATION_36_37);

        assertCoreFixtures(database);
        assertEquals("fixture-repository", scalarString(database, "SELECT stableId FROM Repository WHERE id=7"));
        assertEquals("fixture-item", scalarString(database, "SELECT itemId FROM RepositoryItem WHERE id=11"));
        assertTrue(tableExists(database, "CloudAccount"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM CloudAccount"));
        database.close();
    }

    @Test
    public void migration37To38KeepsRowsAndAddsRepositoryStatusColumns() throws Exception {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 37);
        insertCoreFixtures(database);
        insertRepositoryFixture(database);
        database.close();

        database = helper.runMigrationsAndValidate(TEST_DB, 38, true, Migrations.MIGRATION_37_38);

        assertCoreFixtures(database);
        assertTrue(columnExists(database, "Repository", "lastFailureAt"));
        assertTrue(columnExists(database, "RepositoryItem", "checkStatus"));
        assertTrue(columnExists(database, "RepositoryItem", "lastCheckedAt"));
        assertTrue(columnExists(database, "RepositoryItem", "errorMessage"));
        assertEquals(0, scalarInt(database, "SELECT lastFailureAt FROM Repository WHERE id=7"));
        assertEquals("UNCHECKED", scalarString(database, "SELECT checkStatus FROM RepositoryItem WHERE id=11"));
        assertEquals("", scalarString(database, "SELECT errorMessage FROM RepositoryItem WHERE id=11"));
        database.close();
    }

    @Test
    public void migration35To38ValidatesFullChainWithoutLosingUserData() throws Exception {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 35);
        insertCoreFixtures(database);
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DB,
                38,
                true,
                Migrations.MIGRATION_35_36,
                Migrations.MIGRATION_36_37,
                Migrations.MIGRATION_37_38);

        assertCoreFixtures(database);
        assertTrue(tableExists(database, "Repository"));
        assertTrue(tableExists(database, "RepositoryItem"));
        assertTrue(tableExists(database, "CloudAccount"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM Repository"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM RepositoryItem"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM CloudAccount"));
        database.close();
    }

    @Test
    public void migration21To38PreservesLongLivedTvData() throws Exception {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DB, 21);
        insertVersion21Fixtures(database);
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DB,
                38,
                true,
                Migrations.MIGRATION_21_22,
                Migrations.MIGRATION_22_23,
                Migrations.MIGRATION_23_24,
                Migrations.MIGRATION_24_25,
                Migrations.MIGRATION_25_26,
                Migrations.MIGRATION_26_27,
                Migrations.MIGRATION_27_28,
                Migrations.MIGRATION_28_29,
                Migrations.MIGRATION_29_30,
                Migrations.MIGRATION_30_31,
                Migrations.MIGRATION_31_32,
                Migrations.MIGRATION_32_33,
                Migrations.MIGRATION_33_34,
                Migrations.MIGRATION_34_35,
                Migrations.MIGRATION_35_36,
                Migrations.MIGRATION_36_37,
                Migrations.MIGRATION_37_38);

        assertCoreFixtures(database);
        assertTrue(tableExists(database, "Repository"));
        assertTrue(tableExists(database, "RepositoryItem"));
        assertTrue(tableExists(database, "CloudAccount"));
        assertEquals(0, scalarInt(database, "SELECT COUNT(*) FROM Repository"));
        database.close();
    }

    private static void insertVersion21Fixtures(SupportSQLiteDatabase database) {
        database.execSQL("INSERT INTO Config (id,type,time,url,json,name,home,parse) VALUES (1,0,123,'https://fixture.invalid/config.json','{}','Fixture config','home','')");
        database.execSQL("INSERT INTO Keep (`key`,siteName,vodName,vodPic,createTime,type,cid) VALUES ('keep-key','fixture-site','Fixture keep','poster',123,0,0)");
        database.execSQL("INSERT INTO History (`key`,vodPic,vodName,vodFlag,vodRemarks,episodeUrl,revSort,revPlay,createTime,opening,ending,position,duration,speed,player,scale,cid) VALUES ('history-key','poster','Fixture history','line','Episode 8','episode-url',0,0,123,0,0,456,1000,1.0,2,0,0)");
    }

    private static void insertCoreFixtures(SupportSQLiteDatabase database) {
        database.execSQL("INSERT INTO Config (id,type,time,url,json,name,logo,home,parse) VALUES (1,0,123,'https://fixture.invalid/config.json','{}','Fixture config','','home','')");
        database.execSQL("INSERT INTO Keep (`key`,siteName,vodName,vodPic,createTime,type,cid) VALUES ('keep-key','fixture-site','Fixture keep','poster',123,0,0)");
        database.execSQL("INSERT INTO History (`key`,vodPic,vodName,vodFlag,vodRemarks,episodeUrl,revSort,revPlay,createTime,opening,ending,position,duration,speed,scale,cid) VALUES ('history-key','poster','Fixture history','line','Episode 8','episode-url',0,0,123,0,0,456,1000,1.0,0,0)");
    }

    private static void insertRepositoryFixture(SupportSQLiteDatabase database) {
        database.execSQL("INSERT INTO Repository (id,stableId,name,url,enabled,priority,autoSync,builtIn,lastSyncAt,lastSuccessAt,etag,lastModified,status,errorMessage,createdAt,updatedAt) VALUES (7,'fixture-repository','Fixture repository','https://fixture.invalid/repository.json',1,1,1,0,100,100,'etag','last-modified','READY','',100,100)");
        database.execSQL("INSERT INTO RepositoryItem (id,repositoryId,itemId,name,url,type,sortOrder,enabled,createdAt,updatedAt) VALUES (11,7,'fixture-item','Fixture item','https://fixture.invalid/item.json',0,0,1,100,100)");
    }

    private static void assertCoreFixtures(SupportSQLiteDatabase database) {
        assertEquals("https://fixture.invalid/config.json", scalarString(database, "SELECT url FROM Config WHERE id=1"));
        assertEquals("Fixture keep", scalarString(database, "SELECT vodName FROM Keep WHERE `key`='keep-key'"));
        assertEquals("Fixture history", scalarString(database, "SELECT vodName FROM History WHERE `key`='history-key'"));
        assertEquals(456, scalarInt(database, "SELECT position FROM History WHERE `key`='history-key'"));
    }

    private static boolean tableExists(SupportSQLiteDatabase database, String table) {
        return scalarInt(database, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'") == 1;
    }

    private static boolean indexExists(SupportSQLiteDatabase database, String index) {
        return scalarInt(database, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='" + index + "'") == 1;
    }

    private static boolean columnExists(SupportSQLiteDatabase database, String table, String column) {
        try (Cursor cursor = database.query("PRAGMA table_info(`" + table + "`)")) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) if (column.equals(cursor.getString(nameIndex))) return true;
            return false;
        }
    }

    private static int scalarInt(SupportSQLiteDatabase database, String sql) {
        try (Cursor cursor = database.query(sql)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private static String scalarString(SupportSQLiteDatabase database, String sql) {
        try (Cursor cursor = database.query(sql)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }
}
