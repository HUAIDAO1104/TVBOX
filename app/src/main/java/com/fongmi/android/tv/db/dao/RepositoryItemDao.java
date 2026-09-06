package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.RepositoryItem;

import java.util.List;

@Dao
public abstract class RepositoryItemDao extends BaseDao<RepositoryItem> {

    @Query("SELECT * FROM RepositoryItem WHERE repositoryId = :repositoryId ORDER BY sortOrder ASC, id ASC")
    public abstract List<RepositoryItem> findByRepository(long repositoryId);

    @Query("SELECT * FROM RepositoryItem WHERE repositoryId = :repositoryId AND enabled = 1 ORDER BY sortOrder ASC, id ASC")
    public abstract List<RepositoryItem> findEnabled(long repositoryId);

    @Query("SELECT * FROM RepositoryItem ORDER BY repositoryId ASC, sortOrder ASC, id ASC")
    public abstract List<RepositoryItem> findAll();

    @Query("SELECT COUNT(*) FROM RepositoryItem AS item WHERE item.repositoryId = :repositoryId AND EXISTS (SELECT 1 FROM Config WHERE Config.url = item.url AND Config.type = item.type)")
    public abstract int countMappings(long repositoryId);

    @Query("SELECT COUNT(*) FROM RepositoryItem WHERE repositoryId = :repositoryId")
    public abstract int count(long repositoryId);

    @Query("SELECT COUNT(*) FROM RepositoryItem WHERE repositoryId != :repositoryId AND url = :url AND type = :type")
    public abstract int countShared(long repositoryId, String url, int type);

    @Query("DELETE FROM RepositoryItem WHERE id = :id")
    public abstract void delete(long id);

    @Query("DELETE FROM RepositoryItem WHERE repositoryId = :repositoryId")
    public abstract void deleteByRepository(long repositoryId);

    @Query("DELETE FROM RepositoryItem")
    public abstract void deleteAll();
}
