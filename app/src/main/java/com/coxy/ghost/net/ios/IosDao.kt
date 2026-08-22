package com.coxy.ghost.net.ios

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface IosDao {

    @Query("SELECT * FROM ios_phone ORDER BY nickname")
    suspend fun phones(): List<IosPhone>

    @Query("SELECT * FROM ios_phone WHERE id = :id")
    suspend fun phone(id: Long): IosPhone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(phone: IosPhone): Long

    @Update
    suspend fun update(phone: IosPhone)

    @Query("UPDATE ios_phone SET lastUsedMs = :ms WHERE id = :id")
    suspend fun touch(id: Long, ms: Long)

    @Query("DELETE FROM ios_phone WHERE id = :id")
    suspend fun forget(id: Long)
}
