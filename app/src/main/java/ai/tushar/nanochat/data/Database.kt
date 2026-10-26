package ai.tushar.nanochat.data


import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Update
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: AgentColor,
    val personality: String,
    /** Per-agent overrides of the global generation params; null means "use the default". */
    val temperature: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val createdAt: Long,
)

/** Colors an agent can wear. Stored by name so the palette can be retuned without a migration. */
enum class AgentColor { Coral, Apricot, Sage, Sky, Rose, Plum }

enum class Role { User, Agent }

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = Agent::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("agentId", "createdAt")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    /** Set on agent replies; read only by dev tools. */
    val ttftMs: Long? = null,
    val tokensPerSecond: Float? = null,
    val outputTokens: Int? = null,
)

/** One call to the model, kept for the dev tools' internals sheet and stats page. */
@Entity(
    tableName = "generations",
    foreignKeys = [ForeignKey(entity = Agent::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("agentId"), Index("startedAt")],
)
data class Generation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: Long,
    val startedAt: Long,
    /** [ai.tushar.nanochat.model.BackendKind] name. */
    val backend: String,
    val systemPrompt: String,
    val prompt: String,
    val usedSystemInstruction: Boolean,
    val inputTokens: Int,
    val inputBudget: Int,
    val includedTurns: Int,
    val droppedTurns: Int,
    val temperature: Float,
    val topK: Int,
    val maxOutputTokens: Int,
    val ttftMs: Long?,
    val totalMs: Long,
    val outputTokens: Int,
    val finishReason: String?,
    val error: String?,
)

data class AgentPreview(
    @Embedded val agent: Agent,
    @ColumnInfo(name = "lastText") val lastText: String?,
    @ColumnInfo(name = "lastRole") val lastRole: Role?,
    @ColumnInfo(name = "lastAt") val lastAt: Long?,
)

data class GenerationTotals(
    val count: Int,
    val avgTtftMs: Double?,
    val avgTokensPerSecond: Double?,
    val totalInputTokens: Long?,
    val totalOutputTokens: Long?,
    val errors: Int,
)

@Dao
interface ChatDao {
    @Query(
        """
        SELECT a.*, m.text AS lastText, m.role AS lastRole, m.createdAt AS lastAt
        FROM agents a
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages WHERE agentId = a.id ORDER BY createdAt DESC, id DESC LIMIT 1
        )
        ORDER BY COALESCE(m.createdAt, a.createdAt) DESC
        """,
    )
    fun agentPreviews(): Flow<List<AgentPreview>>

    @Query("SELECT * FROM agents WHERE id = :id")
    fun agent(id: Long): Flow<Agent?>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun agentNow(id: Long): Agent?

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun agentCount(): Int

    @Insert suspend fun insertAgent(agent: Agent): Long
    @Insert suspend fun insertAgents(agents: List<Agent>): List<Long>
    @Update suspend fun updateAgent(agent: Agent)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: Long)

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt, id")
    fun messages(agentId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentMessages(agentId: Long, limit: Int): List<Message>

    @Insert suspend fun insertMessage(message: Message): Long
    @Insert suspend fun insertMessages(messages: List<Message>)

    @Query("DELETE FROM messages WHERE agentId = :agentId")
    suspend fun clearMessages(agentId: Long)

    @Insert suspend fun insertGeneration(generation: Generation): Long

    @Query("SELECT * FROM generations WHERE agentId = :agentId ORDER BY startedAt DESC LIMIT 1")
    fun lastGeneration(agentId: Long): Flow<Generation?>

    @Query("SELECT * FROM generations ORDER BY startedAt DESC LIMIT :limit")
    fun recentGenerations(limit: Int): Flow<List<Generation>>

    @Query(
        """
        SELECT COUNT(*) AS count,
               AVG(ttftMs) AS avgTtftMs,
               AVG(CASE WHEN totalMs > COALESCE(ttftMs, 0) AND outputTokens > 0
                        THEN outputTokens * 1000.0 / (totalMs - COALESCE(ttftMs, 0)) END) AS avgTokensPerSecond,
               SUM(inputTokens) AS totalInputTokens,
               SUM(outputTokens) AS totalOutputTokens,
               SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) AS errors
        FROM generations
        """,
    )
    fun generationTotals(): Flow<GenerationTotals>

    @Query("SELECT COUNT(*) FROM messages")
    fun messageCount(): Flow<Int>
}

@Database(entities = [Agent::class, Message::class, Generation::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder<ChatDatabase>(context, context.getDatabasePath("nano-chat.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
