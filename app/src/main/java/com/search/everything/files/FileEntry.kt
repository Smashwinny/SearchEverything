package com.search.everything.files

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "files")
data class FileEntry(
    @PrimaryKey val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    /** 本次扫描会话号，用于扫完清理已删除的文件 */
    val scanSession: Long,
)
