package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.CloudAccount;

import java.util.List;

@Dao
public abstract class CloudAccountDao extends BaseDao<CloudAccount> {

    @Query("SELECT * FROM CloudAccount ORDER BY provider ASC")
    public abstract List<CloudAccount> findAll();

    @Query("SELECT * FROM CloudAccount WHERE provider = :provider LIMIT 1")
    public abstract CloudAccount find(String provider);

    @Query("DELETE FROM CloudAccount WHERE provider = :provider")
    public abstract void delete(String provider);
}
