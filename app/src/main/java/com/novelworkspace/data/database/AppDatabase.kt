package com.novelworkspace.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vault_files",
    indices = [Index("folderPath"), Index("kind"), Index("lastModifiedMs")]
)
data class VaultFileEntity(
    @PrimaryKey val relativePath: String,
    val name: String,
    val title: String,
    val folderPath: String,
    val kind: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val sha256: String,
    val frontmatterJson: String,
    val bodyText: String,
    val headingsJson: String,
    val parseError: String?
)

@Entity(
    tableName = "note_properties",
    indices = [Index("filePath"), Index("propKey"), Index("propValue")]
)
data class NotePropertyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val propKey: String,
    val propValue: String
)

@Entity(
    tableName = "note_tags",
    indices = [Index("filePath"), Index("tag")]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val tag: String
)

@Entity(
    tableName = "wikilinks",
    indices = [Index("sourcePath"), Index("targetName")]
)
data class WikilinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePath: String,
    val targetName: String,
    val alias: String?
)

@Entity(tableName = "sync_errors")
data class SyncErrorEntity(
    @PrimaryKey val filePath: String,
    val message: String,
    val recordedAtMs: Long
)

@Entity(tableName = "recent_access")
data class RecentAccessEntity(
    @PrimaryKey val relativePath: String,
    val title: String,
    val accessedAtMs: Long
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_files ORDER BY relativePath ASC")
    fun observeAllFiles(): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files ORDER BY relativePath ASC")
    suspend fun getAllFiles(): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files WHERE relativePath = :path LIMIT 1")
    suspend fun getFile(path: String): VaultFileEntity?

    @Query("SELECT * FROM vault_files WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: String): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files ORDER BY lastModifiedMs DESC LIMIT :limit")
    fun observeRecentlyModified(limit: Int = 15): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM recent_access ORDER BY accessedAtMs DESC LIMIT :limit")
    fun observeRecentAccess(limit: Int = 12): Flow<List<RecentAccessEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordAccess(item: RecentAccessEntity)

    @Query("SELECT * FROM note_properties WHERE filePath = :path")
    suspend fun getPropertiesFor(path: String): List<NotePropertyEntity>

    @Query("SELECT * FROM note_properties")
    suspend fun getAllProperties(): List<NotePropertyEntity>

    @Query("SELECT * FROM note_tags")
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM note_tags WHERE filePath = :path")
    suspend fun getTagsFor(path: String): List<TagEntity>

    @Query("SELECT * FROM wikilinks WHERE sourcePath = :path")
    suspend fun getOutgoingLinks(path: String): List<WikilinkEntity>

    @Query("SELECT * FROM wikilinks")
    suspend fun getAllLinks(): List<WikilinkEntity>

    @Query("SELECT * FROM sync_errors ORDER BY recordedAtMs DESC")
    fun observeErrors(): Flow<List<SyncErrorEntity>>

    @Query("SELECT * FROM sync_errors ORDER BY recordedAtMs DESC")
    suspend fun getErrors(): List<SyncErrorEntity>

    @Query(
        """
        SELECT DISTINCT vf.* FROM vault_files vf
        LEFT JOIN note_properties np ON vf.relativePath = np.filePath
        LEFT JOIN note_tags nt ON vf.relativePath = nt.filePath
        LEFT JOIN wikilinks wl ON vf.relativePath = wl.sourcePath
        WHERE vf.name LIKE '%' || :q || '%'
           OR vf.title LIKE '%' || :q || '%'
           OR vf.bodyText LIKE '%' || :q || '%'
           OR np.propValue LIKE '%' || :q || '%'
           OR np.propKey LIKE '%' || :q || '%'
           OR nt.tag LIKE '%' || :q || '%'
           OR wl.targetName LIKE '%' || :q || '%'
        ORDER BY vf.lastModifiedMs DESC
        LIMIT :limit
        """
    )
    suspend fun searchIndexed(q: String, limit: Int = 100): List<VaultFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: VaultFileEntity)

    @Insert
    suspend fun insertProperties(props: List<NotePropertyEntity>)

    @Insert
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert
    suspend fun insertLinks(links: List<WikilinkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertError(err: SyncErrorEntity)

    @Query("DELETE FROM note_properties WHERE filePath = :path")
    suspend fun deletePropertiesFor(path: String)

    @Query("DELETE FROM note_tags WHERE filePath = :path")
    suspend fun deleteTagsFor(path: String)

    @Query("DELETE FROM wikilinks WHERE sourcePath = :path")
    suspend fun deleteLinksFor(path: String)

    @Query("DELETE FROM sync_errors WHERE filePath = :path")
    suspend fun deleteErrorFor(path: String)

    @Query("DELETE FROM vault_files WHERE relativePath = :path")
    suspend fun deleteFileRecord(path: String)

    @Transaction
    suspend fun replaceFileIndex(
        file: VaultFileEntity,
        props: List<NotePropertyEntity>,
        tags: List<TagEntity>,
        links: List<WikilinkEntity>
    ) {
        upsertFile(file)
        deletePropertiesFor(file.relativePath)
        deleteTagsFor(file.relativePath)
        deleteLinksFor(file.relativePath)
        deleteErrorFor(file.relativePath)
        if (props.isNotEmpty()) insertProperties(props)
        if (tags.isNotEmpty()) insertTags(tags)
        if (links.isNotEmpty()) insertLinks(links)
        if (file.parseError != null) {
            upsertError(SyncErrorEntity(file.relativePath, file.parseError, System.currentTimeMillis()))
        }
    }

    @Transaction
    suspend fun removeFileIndex(path: String) {
        deletePropertiesFor(path)
        deleteTagsFor(path)
        deleteLinksFor(path)
        deleteErrorFor(path)
        deleteFileRecord(path)
    }

    @Transaction
    suspend fun clearAll() {
        clearFiles()
        clearProps()
        clearTags()
        clearLinks()
        clearErrors()
    }

    @Query("DELETE FROM vault_files") suspend fun clearFiles()
    @Query("DELETE FROM note_properties") suspend fun clearProps()
    @Query("DELETE FROM note_tags") suspend fun clearTags()
    @Query("DELETE FROM wikilinks") suspend fun clearLinks()
    @Query("DELETE FROM sync_errors") suspend fun clearErrors()
}

@Database(
    entities = [
        VaultFileEntity::class,
        NotePropertyEntity::class,
        TagEntity::class,
        WikilinkEntity::class,
        SyncErrorEntity::class,
        RecentAccessEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_workspace_index.db"
                ).fallbackToDestructiveMigration(true).build().also { instance = it }
            }
        }
    }
}
