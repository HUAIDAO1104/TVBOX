package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.Repository;

import java.util.List;

@Dao
public abstract class RepositoryDao extends BaseDao<Repository> {

    @Query("SELECT * FROM Repository ORDER BY priority ASC, id ASC")
    public abstract List<Repository> findAll();

    @Query("SELECT * FROM Repository WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    public abstract List<Repository> findEnabled();

    @Query("SELECT * FROM Repository WHERE stableId = :stableId LIMIT 1")
    public abstract Repository findByStableId(String stableId);

    @Query("SELECT * FROM Repository WHERE id = :id LIMIT 1")
    public abstract Repository findById(long id);

    @Query("SELECT * FROM Repository WHERE url = :url LIMIT 1")
    public abstract Repository findByUrl(String url);

    @Query("DELETE FROM Repository WHERE id = :id")
    public abstract void delete(long id);

    @Query("DELETE FROM Repository")
    public abstract void deleteAll();
}
