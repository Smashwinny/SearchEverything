package com.search.everything.files

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FileEntry>)

    /**
     * 文件名子串搜索（SQLite LIKE 对 ASCII 不区分大小写，中文按字面匹配）。
     * 前缀命中的排最前，目录排在文件后，再按名字排序。
     */
    @Query(
        """SELECT * FROM files
           WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE
                        WHEN lower(name) LIKE lower(:query) || '%' THEN 0
                        ELSE 1
                    END,
                    isDir, name
           LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int = 200): List<FileEntry>

    @Query("DELETE FROM files WHERE scanSession != :session")
    suspend fun deleteStaleSessions(session: Long): Int

    @Query("SELECT COUNT(*) FROM files")
    suspend fun count(): Int
}
