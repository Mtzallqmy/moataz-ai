package com.mtzallqmy.aiagent.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Observability run record. Never stores chain-of-thought. */
@Entity(tableName = "agent_runs")
data class RunEntity(
    @PrimaryKey val runId: String,
    val agentId: String,
    val provider: String,
    val model: String,
    val startedAt: Long,
    val completedAt: Long?,
    val promptTokens: Int,
    val completionTokens: Int,
    val toolCalls: Int,
    val approvals: Int,
    val errors: Int,
    val estimatedCost: Double,
    val status: String,
)

/** Conversation stored without secrets. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val provider: String,
    val model: String,
)

@Entity(tableName = "conversation_messages",
    indices = [Index("conversationId")])
data class ConversationMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

/** Memory entries with namespaces, metadata, expiry, pinning. No secrets. */
@Entity(tableName = "memories", indices = [Index("namespace")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val namespace: String,
    val type: String, // conversation, working, long_term, procedural, workspace
    val key: String,
    val value: String,
    val metadata: String?,
    val score: Double,
    val pinned: Boolean,
    val expiresAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Skills stored as YAML front matter + Markdown body. */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val name: String,
    val version: String,
    val description: String,
    val requiredCapabilities: String, // comma-separated CapabilityIds
    val instructions: String,
    val enabled: Boolean,
    val source: String, // bundled, user
)

/** Workflow definitions persisted. */
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val workflowId: String,
    val name: String,
    val definitionJson: String,
    val enabled: Boolean,
    val lastRunStatus: String?,
    val lastRunAt: Long?,
)

/** MCP server configuration (no secrets stored here; secrets live in CredentialVault). */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val endpoint: String,
    val transport: String,
    val enabled: Boolean,
    val health: String?,
)

/** Provider configuration (model selections) — no secrets stored. */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val enabled: Boolean,
    val selectedModel: String?,
    val baseUrl: String?,
    val apiVersion: String?,
    val keyCount: Int, // number of keys in vault (never the keys themselves)
)

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: RunEntity)

    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT :limit")
    fun recentRuns(limit: Int = 50): Flow<List<RunEntity>>

    @Query("SELECT * FROM agent_runs WHERE runId = :runId")
    suspend fun get(runId: String): RunEntity?
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun list(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessageEntity)

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun messages(conversationId: String): Flow<List<ConversationMessageEntity>>
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE namespace = :namespace AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY score DESC")
    fun list(namespace: String, now: Long = System.currentTimeMillis()): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE namespace = :namespace AND key = :key AND type = :type AND (expiresAt IS NULL OR expiresAt > :now)")
    suspend fun getByIdentity(namespace: String, type: String, key: String, now: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE namespace = :namespace AND (key LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%') AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY pinned DESC, score DESC, updatedAt DESC LIMIT :limit")
    suspend fun search(namespace: String, query: String, now: Long, limit: Int): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills")
    fun list(): Flow<List<SkillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface WorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: WorkflowEntity)

    @Query("SELECT * FROM workflows")
    fun list(): Flow<List<WorkflowEntity>>

    @Query("UPDATE workflows SET lastRunStatus = :status, lastRunAt = :now WHERE workflowId = :workflowId")
    suspend fun markRun(workflowId: String, status: String, now: Long)
}

@Dao
interface McpServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("SELECT * FROM mcp_servers")
    fun list(): Flow<List<McpServerEntity>>
}

@Dao
interface ProviderConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ProviderConfigEntity)

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId")
    fun get(providerId: String): Flow<ProviderConfigEntity?>
}

@Database(
    entities = [
        RunEntity::class, ConversationEntity::class, ConversationMessageEntity::class,
        MemoryEntity::class, SkillEntity::class, WorkflowEntity::class,
        McpServerEntity::class, ProviderConfigEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun providerConfigDao(): ProviderConfigDao
}
