package com.fongmi.android.tv.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.CloudAccount;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.cloud.CloudCredentialBridge;
import com.fongmi.android.tv.db.dao.ConfigDao;
import com.fongmi.android.tv.db.dao.CloudAccountDao;
import com.fongmi.android.tv.db.dao.DeviceDao;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.db.dao.KeepDao;
import com.fongmi.android.tv.db.dao.LiveDao;
import com.fongmi.android.tv.db.dao.RepositoryDao;
import com.fongmi.android.tv.db.dao.RepositoryItemDao;
import com.fongmi.android.tv.db.dao.SiteDao;
import com.fongmi.android.tv.db.dao.TrackDao;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Database(entities = {Keep.class, Site.class, Live.class, Track.class, Config.class, Device.class, History.class, Repository.class, RepositoryItem.class, CloudAccount.class}, version = AppDatabase.VERSION)
public abstract class AppDatabase extends RoomDatabase {

    public static final int VERSION = 38;
    public static final String NAME = "tv";
    public static final String SYMBOL = "@@@";

    private static volatile AppDatabase instance;

    public static synchronized AppDatabase get() {
        if (instance == null) instance = create(App.get());
        return instance;
    }

    public static void backup() {
        backup(new com.fongmi.android.tv.impl.Callback());
    }

    private static final java.util.concurrent.atomic.AtomicBoolean backingUp = new java.util.concurrent.atomic.AtomicBoolean();

    public static void backup(com.fongmi.android.tv.impl.Callback callback) {
        if (!backingUp.compareAndSet(false, true)) { App.post(callback::error); return; }
        Task.execute(() -> {
            File temporary = new File(Path.tv(), ".backup-" + System.nanoTime() + ".tmp");
            try {
                Backup backup = Backup.create();
                if (backup.getConfig().isEmpty()) throw new java.io.IOException("Empty backup");
                File destination = new File(Path.tv(), "tv-" + LocalDate.now().format(Formatters.DATE) + ".bk.gz");
                try (java.io.FileOutputStream file = new java.io.FileOutputStream(temporary);
                     java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(file)) {
                    zip.write(backup.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.finish();
                    file.getFD().sync();
                }
                if (!temporary.renameTo(destination)) throw new java.io.IOException("Cannot finish backup");
                App.post(callback::success);
                cleanOld();
            } catch (Exception error) {
                App.post(callback::error);
            } finally {
                temporary.delete();
                backingUp.set(false);
            }
        });
    }

    public static void restore(File file, com.fongmi.android.tv.impl.Callback callback) {
        Task.execute(() -> {
            File restore = Path.cache("restore");
            try {
                FileUtil.gzipDecompress(file, restore);
                Backup backup = Backup.objectFrom(Path.read(restore));
                if (backup.getConfig().isEmpty()) {
                    App.post(callback::error);
                    return;
                }
                backup.restore();
                CloudCredentialBridge.clear();
                RepositoryManager.get().reinitializeAfterRestore();
                LiveConfig.load(Config.live(), new com.fongmi.android.tv.impl.Callback());
                VodConfig.load(Config.vod(), new com.fongmi.android.tv.impl.Callback() {
                    private void complete() {
                        RefreshEvent.home();
                        RefreshEvent.category();
                        App.post(callback::success);
                    }

                    @Override
                    public void success() {
                        complete();
                    }

                    @Override
                    public void error(String msg) {
                        complete();
                    }
                });
            } catch (Throwable error) {
                App.post(callback::error);
            } finally {
                Path.clear(restore);
            }
        });
    }

    private static void cleanOld() {
        List<File> items = new ArrayList<>();
        File[] files = Path.tv().listFiles();
        if (files == null) files = new File[0];
        for (File file : files) if (file.getName().startsWith("tv") && file.getName().endsWith(".bk.gz")) items.add(file);
        if (!items.isEmpty()) items.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        if (items.size() > 7) for (int i = 7; i < items.size(); i++) Path.clear(items.get(i));
    }

    private static AppDatabase create(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, NAME)
                .addMigrations(Migrations.MIGRATION_5_6)
                .addMigrations(Migrations.MIGRATION_6_7)
                .addMigrations(Migrations.MIGRATION_7_8)
                .addMigrations(Migrations.MIGRATION_8_9)
                .addMigrations(Migrations.MIGRATION_9_10)
                .addMigrations(Migrations.MIGRATION_10_11)
                .addMigrations(Migrations.MIGRATION_11_12)
                .addMigrations(Migrations.MIGRATION_12_13)
                .addMigrations(Migrations.MIGRATION_13_14)
                .addMigrations(Migrations.MIGRATION_14_15)
                .addMigrations(Migrations.MIGRATION_15_16)
                .addMigrations(Migrations.MIGRATION_16_17)
                .addMigrations(Migrations.MIGRATION_17_18)
                .addMigrations(Migrations.MIGRATION_18_19)
                .addMigrations(Migrations.MIGRATION_19_20)
                .addMigrations(Migrations.MIGRATION_20_21)
                .addMigrations(Migrations.MIGRATION_21_22)
                .addMigrations(Migrations.MIGRATION_22_23)
                .addMigrations(Migrations.MIGRATION_23_24)
                .addMigrations(Migrations.MIGRATION_24_25)
                .addMigrations(Migrations.MIGRATION_25_26)
                .addMigrations(Migrations.MIGRATION_26_27)
                .addMigrations(Migrations.MIGRATION_27_28)
                .addMigrations(Migrations.MIGRATION_28_29)
                .addMigrations(Migrations.MIGRATION_29_30)
                .addMigrations(Migrations.MIGRATION_30_31)
                .addMigrations(Migrations.MIGRATION_31_32)
                .addMigrations(Migrations.MIGRATION_32_33)
                .addMigrations(Migrations.MIGRATION_33_34)
                .addMigrations(Migrations.MIGRATION_34_35)
                .addMigrations(Migrations.MIGRATION_35_36)
                .addMigrations(Migrations.MIGRATION_36_37)
                .addMigrations(Migrations.MIGRATION_37_38)
                // Versions 1-4 predate the oldest schema which can be migrated safely. This
                // matches the historical app behavior for those releases, while preserving all
                // user data from version 5 onward through the explicit chain above.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                .allowMainThreadQueries().build();
    }

    public abstract KeepDao getKeepDao();

    public abstract SiteDao getSiteDao();

    public abstract LiveDao getLiveDao();

    public abstract TrackDao getTrackDao();

    public abstract ConfigDao getConfigDao();

    public abstract DeviceDao getDeviceDao();

    public abstract HistoryDao getHistoryDao();

    public abstract RepositoryDao getRepositoryDao();

    public abstract RepositoryItemDao getRepositoryItemDao();

    public abstract CloudAccountDao getCloudAccountDao();
}
