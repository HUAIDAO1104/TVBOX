package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;
import com.fongmi.android.tv.history.AccountSession;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    private static final String SAFE_COLUMNS = "`key`, vodPic, vodName, vodFlag, vodRemarks, "
            + "CASE WHEN length(episodeUrl) <= 16384 THEN episodeUrl ELSE '' END AS episodeUrl, "
            + "revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid, profile";

    private static final String MERGE_COLUMNS = "`key`, '' AS vodPic, vodName, vodFlag, vodRemarks, "
            + "'' AS episodeUrl, revSort, revPlay, createTime, opening, ending, position, duration, "
            + "speed, scale, cid, profile";

    @Query("SELECT " + SAFE_COLUMNS + " FROM History WHERE profile=:profile AND cid = :cid AND `key` > :after ORDER BY `key` LIMIT 32")
    public abstract List<History> pageScoped(String profile, int cid, String after);

    public List<History> page(int cid, String after) {
        return pageScoped(AccountSession.profile(), cid, after);
    }

    @Query("INSERT OR IGNORE INTO History (profile, `key`, vodPic, vodName, vodFlag, vodRemarks, episodeUrl, revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid) SELECT :profile, `key`, vodPic, vodName, vodFlag, vodRemarks, episodeUrl, revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid FROM History WHERE profile=''")
    public abstract void importGuest(String profile);

    @Query("SELECT * FROM History WHERE profile=:profile")
    public abstract List<History> findAllScoped(String profile);

    public List<History> findAll() {
        return findAllScoped(AccountSession.profile());
    }

    @Query("SELECT " + SAFE_COLUMNS + " FROM History WHERE profile=:profile AND cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> findScoped(String profile, int cid, long createTime);

    public List<History> find(int cid, long createTime) {
        return findScoped(AccountSession.profile(), cid, createTime);
    }

    @Query("SELECT " + SAFE_COLUMNS + " FROM History WHERE profile=:profile AND cid = :cid AND `key` = :key")
    public abstract History findScoped(String profile, int cid, String key);

    public History find(int cid, String key) {
        return findScoped(AccountSession.profile(), cid, key);
    }

    // Merge only needs playback metadata. Excluding poster and episode URL prevents old Android
    // CursorWindow implementations from loading megabytes of source payload during progress saves.
    @Query("SELECT " + MERGE_COLUMNS + " FROM History WHERE profile=:profile AND cid = :cid AND vodName = :vodName ORDER BY createTime DESC LIMIT 32")
    public abstract List<History> findByNameScoped(String profile, int cid, String vodName);

    public List<History> findByName(int cid, String vodName) {
        return findByNameScoped(AccountSession.profile(), cid, vodName);
    }

    @Query("DELETE FROM History WHERE profile=:profile AND cid = :cid AND `key` = :key")
    public abstract void deleteScoped(String profile, int cid, String key);

    public void delete(int cid, String key) {
        deleteScoped(AccountSession.profile(), cid, key);
    }

    @Query("DELETE FROM History WHERE profile=:profile AND cid = :cid")
    public abstract void deleteScoped(String profile, int cid);

    public void delete(int cid) {
        deleteScoped(AccountSession.profile(), cid);
    }

    @Query("DELETE FROM History WHERE profile=:profile")
    public abstract void deleteScoped(String profile);

    public void delete() {
        deleteScoped(AccountSession.profile());
    }

    public void deleteAll() {
        delete();
    }
}
