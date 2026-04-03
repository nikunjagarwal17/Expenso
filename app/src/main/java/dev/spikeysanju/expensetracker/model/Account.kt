package dev.spikeysanju.expensetracker.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable
import java.util.UUID

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "name")
    var name: String,
    @ColumnInfo(name = "balance")
    var balance: Double = 0.0,
) : Serializable
