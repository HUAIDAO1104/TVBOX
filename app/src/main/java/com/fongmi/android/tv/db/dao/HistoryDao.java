package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    private static final String SAFE_COLUMNS = "`key`, vodPic, vodName, vodFlag, vodRemarks, "
            + "CASE WHEN length(episodeUrl) <= 16384 THEN episodeUrl ELSE '' END AS episodeUrl, "
            + "revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid";

    private static final String MERGE_COLUMNS = "`key`, '' AS vodPic, vodName, vodFlag, vodRemarks, "
            + "'' AS episodeUrl, revSort, revPlay, createTime, opening, ending, position, duration, "
            + "speed, scale, cid";

    @Query("SELECT * FROM History")
    public abstract List<History> findAll();

    @Query("SELECT " + SAFE_COLUMNS + " FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> find(int cid, long createTime);

    @Query("SELECT " + SAFE_COLUMNS + " FROM History WHERE cid = :cid AND `key` = :key")
    public abstract History find(int cid, String key);

    // Merge only needs playback metadata. Excluding poster and episode URL prevents old Android
    // CursorWindow implementations from loading megabytes of source payload during progress saves.
    @Query("SELECT " + MERGE_COLUMNS + " FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC LIMIT 32")
    public abstract List<History> findByName(int cid, String vodName);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    public abstract void delete(int cid, String key);

    @Query("DELETE FROM History WHERE cid = :cid")
    public abstract void delete(int cid);

    @Query("DELETE FROM History")
    public abstract void delete();

    @Query("DELETE FROM History")
    public abstract void deleteAll();
}
