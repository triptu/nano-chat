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
import androidx.room3.Transaction
import androidx.room3.RoomDatabase
import androidx.room3.Update
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
    /** [ai.tushar.nanochat.model.ModelId] name; null means "use the default model from the nerds page". */
    @ColumnInfo(defaultValue = "NULL") val model: String? = null,
    /** "Use this phone": the companion may call Handset's tools. */
    @ColumnInfo(defaultValue = "0") val useHandset: Boolean = false,
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
    /** [ai.tushar.nanochat.model.ModelId] name and "GPU"/"CPU", set on agent replies. */
    @ColumnInfo(defaultValue = "NULL") val model: String? = null,
    @ColumnInfo(defaultValue = "NULL") val accelerator: String? = null,
)

enum class AttachmentKind { Image, Audio }

/**
 * A photo or voice note on a user message. Files live in app-private storage under `attachments/`;
 * [file] is what the chat shows or plays, [modelFile] is the copy sent to the model (a downscaled PNG for photos).
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = Message::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val kind: AttachmentKind,
    val file: String,
    val modelFile: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

/**
 * One Handset tool a reply called, shown as a line under the reply. [result] is what came back (kept up to
 * [ToolStep.MaxResultChars]); the model was sent its first [sentChars] characters.
 */
@Entity(
    tableName = "tool_steps",
    foreignKeys = [ForeignKey(entity = Message::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class ToolStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val position: Int,
    val tool: String,
    /** The call's arguments as JSON. */
    val arguments: String,
    val label: String,
    /** [ai.tushar.nanochat.handset.StepOutcome] name. */
    val outcome: String,
    val result: String,
    val sentChars: Int,
) {
    val cut: Boolean get() = sentChars < result.length

    companion object {
        const val MaxResultChars = 20_000
    }
}

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
    @ColumnInfo(defaultValue = "0") val usedPrefixCache: Boolean = false,
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
    @ColumnInfo(defaultValue = "NULL") val model: String? = null,
    @ColumnInfo(defaultValue = "NULL") val accelerator: String? = null,
    /** How long loading the model took when this request had to load it first. */
    @ColumnInfo(defaultValue = "NULL") val loadMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val prefillTokensPerSecond: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val decodeTokensPerSecond: Float? = null,
    @ColumnInfo(defaultValue = "0") val imagesSent: Int = 0,
    @ColumnInfo(defaultValue = "0") val imagesDropped: Int = 0,
    @ColumnInfo(defaultValue = "0") val audioSent: Int = 0,
    @ColumnInfo(defaultValue = "0") val audioDropped: Int = 0,
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

    @Query("SELECT COUNT(*) FROM agents WHERE useHandset = 1")
    suspend fun handsetAgentCount(): Int

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: Long)

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt, id")
    fun messages(agentId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentMessages(agentId: Long, limit: Int): List<Message>

    /** Companions whose newest message is the user's, so it has no reply yet. */
    @Query(
        """
        SELECT a.* FROM agents a
        JOIN messages m ON m.id = (
            SELECT id FROM messages WHERE agentId = a.id ORDER BY createdAt DESC, id DESC LIMIT 1
        )
        WHERE m.role = 'User'
        """,
    )
    suspend fun agentsAwaitingReply(): List<Agent>

    @Insert suspend fun insertMessage(message: Message): Long

    @Transaction
    suspend fun insertReply(message: Message, steps: List<ToolStep>): Long {
        val id = insertMessage(message)
        if (steps.isNotEmpty()) insertToolSteps(steps.mapIndexed { i, s -> s.copy(messageId = id, position = i) })
        return id
    }

    /** Saves a reply in place of [old], the reply it continues, when there is one. */
    @Transaction
    suspend fun replaceReply(old: Long?, message: Message, steps: List<ToolStep>): Long {
        if (old != null) deleteMessage(old)
        return insertReply(message, steps)
    }

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Insert suspend fun insertMessages(messages: List<Message>)

    @Query("DELETE FROM messages WHERE agentId = :agentId")
    suspend fun clearMessages(agentId: Long)

    @Insert suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT at.* FROM attachments at JOIN messages m ON m.id = at.messageId WHERE m.agentId = :agentId ORDER BY at.id")
    fun attachments(agentId: Long): Flow<List<Attachment>>

    @Query("SELECT at.* FROM attachments at JOIN messages m ON m.id = at.messageId WHERE m.agentId = :agentId ORDER BY at.id")
    suspend fun attachmentsNow(agentId: Long): List<Attachment>

    /** Every attachment file name still referenced, so unreferenced files can be cleaned up. */
    @Query("SELECT file FROM attachments UNION SELECT modelFile FROM attachments")
    suspend fun attachmentFiles(): List<String>

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

    @Insert suspend fun insertToolSteps(steps: List<ToolStep>)

    @Query("SELECT s.* FROM tool_steps s JOIN messages m ON m.id = s.messageId WHERE m.agentId = :agentId ORDER BY s.messageId, s.position")
    fun toolSteps(agentId: Long): Flow<List<ToolStep>>

    @Query("SELECT * FROM tool_steps WHERE messageId = :messageId ORDER BY position")
    suspend fun toolStepsFor(messageId: Long): List<ToolStep>
}

private object AddPrefixCacheColumn : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE generations ADD COLUMN usedPrefixCache INTEGER NOT NULL DEFAULT 0")
    }
}

private object AddModelsAndAttachments : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE agents ADD COLUMN model TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE messages ADD COLUMN model TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE messages ADD COLUMN accelerator TEXT DEFAULT NULL")
        for (col in listOf("model TEXT", "accelerator TEXT", "loadMs INTEGER", "prefillTokensPerSecond REAL", "decodeTokensPerSecond REAL")) {
            connection.execSQL("ALTER TABLE generations ADD COLUMN $col DEFAULT NULL")
        }
        for (col in listOf("imagesSent", "imagesDropped", "audioSent", "audioDropped")) {
            connection.execSQL("ALTER TABLE generations ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
        }
        // Old rows were all Gemini Nano.
        connection.execSQL("UPDATE generations SET model = 'Nano' WHERE backend = 'Real'")
        connection.execSQL("UPDATE messages SET model = 'Nano' WHERE role = 'Agent'")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, messageId INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, file TEXT NOT NULL, modelFile TEXT NOT NULL, width INTEGER, height INTEGER, durationMs INTEGER, " +
                "FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_messageId ON attachments (messageId)")
    }
}

private object AddHandset : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE agents ADD COLUMN useHandset INTEGER NOT NULL DEFAULT 0")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS tool_steps (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, messageId INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, tool TEXT NOT NULL, arguments TEXT NOT NULL, label TEXT NOT NULL, outcome TEXT NOT NULL, " +
                "result TEXT NOT NULL, sentChars INTEGER NOT NULL, " +
                "FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tool_steps_messageId ON tool_steps (messageId)")
    }
}

@Database(entities = [Agent::class, Message::class, Generation::class, Attachment::class, ToolStep::class], version = 4, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder<ChatDatabase>(context, context.getDatabasePath("nano-chat.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(AddPrefixCacheColumn, AddModelsAndAttachments, AddHandset)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
