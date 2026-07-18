package com.fongmi.android.tv.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.github.catvod.utils.Prefers;

public class Migrations {

    /*
     * Keep the complete migration chain used by released builds. TV devices commonly retain
     * application data for years and are then upgraded directly, without installing every
     * intermediate APK. Removing an older migration makes Room fail the first database query;
     * Home then remains on its loading shell and opening repository settings crashes.
     */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN revSort INTEGER DEFAULT 0 NOT NULL");
            database.execSQL("ALTER TABLE History ADD COLUMN revPlay INTEGER DEFAULT 0 NOT NULL");
        }
    };

    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN position INTEGER DEFAULT 0 NOT NULL");
            database.execSQL("UPDATE History SET position = duration");
            database.execSQL("UPDATE History SET duration = 0");
        }
    };

    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Config ADD COLUMN json TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `Keep` (`key` TEXT NOT NULL, `siteName` TEXT, `vodName` TEXT, `vodPic` TEXT, `createTime` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
        }
    };

    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("UPDATE `History` SET ending = 0");
        }
    };

    public static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Versions 10 and 11 share the same Room schema.
        }
    };

    public static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Config ADD COLUMN type INTEGER DEFAULT 0 NOT NULL");
            database.execSQL("ALTER TABLE Config ADD COLUMN home TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Keep ADD COLUMN type INTEGER DEFAULT 0 NOT NULL");
        }
    };

    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS index_Config_url");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Config_url_type ON Config(url, type)");
        }
    };

    public static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN scale INTEGER DEFAULT -1 NOT NULL");
        }
    };

    public static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN speed REAL DEFAULT 1 NOT NULL");
            database.execSQL("ALTER TABLE History ADD COLUMN player INTEGER DEFAULT -1 NOT NULL");
        }
    };

    public static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `Track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `player` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_player_type` ON `Track` (`key`, `player`, `type`)");
        }
    };

    public static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Config ADD COLUMN parse TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Site ADD COLUMN changeable INTEGER DEFAULT 1");
        }
    };

    public static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Config ADD COLUMN name TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `Device` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `uuid` TEXT, `name` TEXT, `ip` TEXT)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Device_uuid_name` ON `Device` (`uuid`, `name`)");
        }
    };

    public static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Device ADD COLUMN type INTEGER DEFAULT 0 NOT NULL");
        }
    };

    public static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("UPDATE History SET player = 2 WHERE player = 0");
            if (Prefers.getInt("player", 2) == 0) Prefers.put("player", 2);
            if (Prefers.getInt("player_live", Prefers.getInt("player", 2)) == 0) Prefers.put("player_live", 2);
        }
    };

    public static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Track ADD COLUMN `adaptive` INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Site ADD COLUMN recordable INTEGER DEFAULT 1");
        }
    };

    public static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE Site_Backup (`key` TEXT NOT NULL, name TEXT, searchable INTEGER, changeable INTEGER, recordable INTEGER, PRIMARY KEY (`key`))");
            database.execSQL("INSERT INTO Site_Backup SELECT `key`, name, searchable, changeable, recordable FROM Site");
            database.execSQL("DROP TABLE Site");
            database.execSQL("ALTER TABLE Site_Backup RENAME to Site");
        }
    };

    public static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `Live` (`name` TEXT NOT NULL, `boot` INTEGER NOT NULL, `pass` INTEGER NOT NULL, PRIMARY KEY(`name`))");
        }
    };

    public static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            Prefers.remove("danmu_size");
        }
    };

    public static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE Site_Backup (`key` TEXT NOT NULL, searchable INTEGER, changeable INTEGER, PRIMARY KEY (`key`))");
            database.execSQL("INSERT INTO Site_Backup SELECT `key`, searchable, changeable FROM Site");
            database.execSQL("DROP TABLE Site");
            database.execSQL("ALTER TABLE Site_Backup RENAME to Site");
        }
    };

    public static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Config ADD COLUMN logo TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL, `adaptive` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            database.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History");
            database.execSQL("DROP TABLE History");
            database.execSQL("ALTER TABLE History_Backup RENAME to History");
        }
    };

    public static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `Repository` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stableId` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `autoSync` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `lastSyncAt` INTEGER NOT NULL, `lastSuccessAt` INTEGER NOT NULL, `etag` TEXT NOT NULL, `lastModified` TEXT NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Repository_stableId` ON `Repository` (`stableId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_Repository_priority` ON `Repository` (`priority`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `RepositoryItem` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `repositoryId` INTEGER NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`repositoryId`) REFERENCES `Repository`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_RepositoryItem_repositoryId` ON `RepositoryItem` (`repositoryId`)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_RepositoryItem_repositoryId_url_type` ON `RepositoryItem` (`repositoryId`, `url`, `type`)");
        }
    };

    public static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `CloudAccount` (`provider` TEXT NOT NULL, `accountId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `encryptedCredential` TEXT NOT NULL, `credentialType` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `lastVerifiedAt` INTEGER NOT NULL, PRIMARY KEY(`provider`))");
        }
    };

    public static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `Repository` ADD COLUMN `lastFailureAt` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `RepositoryItem` ADD COLUMN `checkStatus` TEXT NOT NULL DEFAULT 'UNCHECKED'");
            database.execSQL("ALTER TABLE `RepositoryItem` ADD COLUMN `lastCheckedAt` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `RepositoryItem` ADD COLUMN `errorMessage` TEXT NOT NULL DEFAULT ''");
        }
    };
}
