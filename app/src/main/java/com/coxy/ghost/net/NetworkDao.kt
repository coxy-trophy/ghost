package com.coxy.ghost.net

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NetworkDao {

    // --- networks --------------------------------------------------------------------------------

    @Query("SELECT * FROM known_network WHERE gatewayMac = :mac LIMIT 1")
    suspend fun networkByGateway(mac: String): KnownNetwork?

    @Query("SELECT * FROM known_network ORDER BY lastSeenMs DESC")
    suspend fun networks(): List<KnownNetwork>

    @Query("SELECT * FROM known_network WHERE id = :id")
    suspend fun network(id: Long): KnownNetwork?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNetwork(network: KnownNetwork): Long

    @Update
    suspend fun updateNetwork(network: KnownNetwork)

    @Query("UPDATE known_network SET nickname = :nickname WHERE id = :id")
    suspend fun nameNetwork(id: Long, nickname: String?)

    @Query("DELETE FROM known_network WHERE id = :id")
    suspend fun forgetNetwork(id: Long)

    // --- devices ---------------------------------------------------------------------------------

    @Query("SELECT * FROM known_device WHERE networkId = :networkId ORDER BY lastSeenMs DESC")
    suspend fun devices(networkId: Long): List<KnownDevice>

    /**
     * Everything ever seen anywhere, newest first.
     *
     * Used when resolving a name while off Wi-Fi or on a network the device was not seen on: being
     * able to say "the office TV, but you're not on that network" is a better answer than "no such
     * device", and it is the difference between a name being wrong and being out of reach.
     */
    @Query("SELECT * FROM known_device ORDER BY lastSeenMs DESC")
    suspend fun allDevices(): List<KnownDevice>

    @Query("SELECT * FROM known_device WHERE networkId = :networkId AND stableKey = :key LIMIT 1")
    suspend fun device(networkId: Long, key: String): KnownDevice?

    @Query("SELECT * FROM known_device WHERE id = :id")
    suspend fun deviceById(id: Long): KnownDevice?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevice(device: KnownDevice): Long

    @Update
    suspend fun updateDevice(device: KnownDevice)

    @Query("UPDATE known_device SET nickname = :nickname WHERE id = :id")
    suspend fun nameDevice(id: Long, nickname: String?)

    @Query("UPDATE known_device SET paired = :paired WHERE id = :id")
    suspend fun setPaired(id: Long, paired: Boolean)

    @Query("DELETE FROM known_device WHERE id = :id")
    suspend fun forgetDevice(id: Long)

    @Query("DELETE FROM known_device WHERE networkId = :networkId")
    suspend fun forgetDevicesOn(networkId: Long)

    @Query("SELECT COUNT(*) FROM known_device WHERE networkId = :networkId")
    suspend fun deviceCount(networkId: Long): Int
}
