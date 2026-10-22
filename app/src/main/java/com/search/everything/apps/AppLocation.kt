package com.search.everything.apps

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 一个应用在桌面上的位置：第几屏、是否在文件夹里、网格行列 / Dock 栏 */
@Entity(tableName = "app_locations")
data class AppLocation(
    /** 桌面上显示的图标名称（与 PackageManager label 可能略有差异，按此匹配） */
    @PrimaryKey val label: String,
    val page: Int,              // 从 1 开始
    val folderName: String?,    // null = 直接在桌面上
    val row: Int?,              // 桌面网格行列（文件夹内为 null）
    val col: Int?,
    val isDock: Boolean = false,
    val isFolder: Boolean = false,  // 记录的是文件夹本身（用户也可能想找文件夹在哪）
    val indexInFolder: Int = 0, // 文件夹内第几个（从 1 开始）
)

@Dao
interface AppLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(locations: List<AppLocation>)

    @Query("DELETE FROM app_locations")
    suspend fun clear()

    @Query("SELECT * FROM app_locations")
    suspend fun all(): List<AppLocation>

    @Query("SELECT COUNT(*) FROM app_locations")
    suspend fun count(): Int
}
