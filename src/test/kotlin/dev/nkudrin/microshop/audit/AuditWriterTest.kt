package dev.nkudrin.microshop.audit

import dev.nkudrin.microshop.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuditWriterTest {
    @Autowired
    private lateinit var writer: AuditWriter

    @Autowired
    private lateinit var repository: AuditLogRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `commit saves operation and safe snapshots with actor and timestamp through JPA`() {
        val adminId = prepareAdmin()
        val startedAt = Instant.now()
        val before = snapshotSource("Original")
        val after = snapshotSource("Updated")
        val auditId =
            TransactionTemplate(transactionManager).execute {
                changeAdmin(adminId)
                writer.record(
                    actorType = AuditActorType.ADMIN,
                    actorAdminId = adminId,
                    action = "TEST_UPDATE",
                    entityType = "admin_user",
                    entityId = adminId.toString(),
                    before = AuditSnapshot.select(before, "display_name", "enabled", "count", "note"),
                    after = AuditSnapshot.select(after, "display_name", "enabled", "count", "note"),
                )
            }
        val finishedAt = Instant.now()

        assertEquals("Updated", adminName(adminId))
        val entry = repository.findById(auditId).orElseThrow()
        assertEquals(AuditActorType.ADMIN, entry.actorType)
        assertEquals(adminId, entry.actorAdminId)
        assertEquals("TEST_UPDATE", entry.action)
        assertEquals("admin_user", entry.entityType)
        assertEquals(adminId.toString(), entry.entityId)
        assertEquals(mapOf("display_name" to "Original", "enabled" to true, "count" to 3, "note" to null), entry.beforeData)
        assertEquals(mapOf("display_name" to "Updated", "enabled" to true, "count" to 3, "note" to null), entry.afterData)
        assertTrue(entry.createdAt >= startedAt && entry.createdAt <= finishedAt)
        assertEquals(
            entry.createdAt,
            jdbc.queryForObject("SELECT created_at FROM audit_log WHERE id = ?", OffsetDateTime::class.java, auditId)!!.toInstant(),
        )
        val json =
            jdbc.queryForMap(
                "SELECT before_data::text, after_data::text, jsonb_typeof(before_data) AS json_type FROM audit_log WHERE id = ?",
                auditId,
            )
        assertEquals("object", json["json_type"])
        listOf("password_hash", "token", "secrets", "control-password", "control-token", "control-secret").forEach {
            assertTrue(json.values.none { value -> value.toString().contains(it) })
        }
    }

    @Test
    fun `rollback removes both operation and already flushed audit`() {
        val adminId = prepareAdmin()
        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                changeAdmin(adminId)
                recordAdmin(adminId)
                assertEquals(1, auditCount())
                error("Test operation failed")
            }
        }
        assertEquals("Original", adminName(adminId))
        assertEquals(0, auditCount())
    }

    @Test
    fun `audit failure marks operation rollback only even when caller catches it`() {
        val adminId = prepareAdmin()
        assertFailsWith<UnexpectedRollbackException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                changeAdmin(adminId)
                assertSqlState("23503") { recordAdmin(UUID.randomUUID()) }
            }
        }
        assertEquals("Original", adminName(adminId))
        assertEquals(0, auditCount())
    }

    @Test
    fun `audit cannot create an independent transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            writer.record(AuditActorType.SYSTEM, null, "TEST_UPDATE", "test_object", "42")
        }
        assertEquals(0, auditCount())
    }

    @Test
    fun `system audit allows absent admin and nullable snapshots`() {
        val auditId =
            TransactionTemplate(transactionManager).execute {
                writer.record(AuditActorType.SYSTEM, null, "TEST_UPDATE", "test_object", "42")
            }
        val entry = repository.findById(auditId).orElseThrow()
        assertEquals(AuditActorType.SYSTEM, entry.actorType)
        assertNull(entry.actorAdminId)
        assertNull(entry.beforeData)
        assertNull(entry.afterData)
        assertEquals("42", entry.entityId)
    }

    @Test
    fun `database enforces audit constraints and restricts deletion of its actor`() {
        val adminId = prepareAdmin()
        val valid = auditValues(adminId)
        insertAudit(valid)
        assertSqlState("23505") { insertAudit(valid) }
        listOf(0, 1, 3, 4, 5, 8).forEach { index ->
            assertSqlState("23502") { insertAudit(auditValues(adminId).also { it[index] = null }) }
        }
        assertSqlState("23514") { insertAudit(auditValues(adminId).also { it[1] = "UNKNOWN" }) }
        assertSqlState("23514") { insertAudit(auditValues(adminId).also { it[2] = null }) }
        assertSqlState("23503") { insertAudit(auditValues(UUID.randomUUID())) }
        assertSqlState("22P02") { insertAudit(auditValues(adminId).also { it[6] = "invalid-json" }) }
        assertSqlState("23001") { jdbc.update("DELETE FROM admin_user WHERE id = ?", adminId) }

        insertAudit(auditValues(null).also { it[1] = "SYSTEM" })
        insertAudit(auditValues(adminId).also { it[1] = "SYSTEM" })
        assertEquals(3, auditCount())
        val indexes =
            jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'audit_log'",
                String::class.java,
            )
        listOf("(entity_type, entity_id, created_at, id)", "(created_at, id)", "(actor_admin_id)").forEach { columns ->
            assertTrue(indexes.any { it?.endsWith(columns) == true })
        }
    }

    private fun prepareAdmin(): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            "INSERT INTO admin_user (id, login, password_hash, display_name, enabled, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            "test-owner",
            "test-only-hash",
            "Original",
            true,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
        )
    }

    private fun changeAdmin(adminId: UUID) {
        assertEquals(1, jdbc.update("UPDATE admin_user SET display_name = 'Updated' WHERE id = ?", adminId))
    }

    private fun adminName(adminId: UUID): String? =
        jdbc.queryForObject("SELECT display_name FROM admin_user WHERE id = ?", String::class.java, adminId)

    private fun recordAdmin(adminId: UUID) = writer.record(AuditActorType.ADMIN, adminId, "TEST_UPDATE", "admin_user", adminId.toString())

    private fun auditCount(): Int? = jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java)

    private fun snapshotSource(name: String): Map<String, Any?> = mapOf(
        "display_name" to name,
        "enabled" to true,
        "count" to 3,
        "note" to null,
        "password_hash" to "control-password",
        "token" to "control-token",
        "secrets" to mapOf("nested" to "control-secret"),
    )

    private fun auditValues(adminId: UUID?): MutableList<Any?> = mutableListOf(
        UUID.randomUUID(),
        "ADMIN",
        adminId,
        "TEST_UPDATE",
        "test_object",
        "42",
        null,
        null,
        OffsetDateTime.now(),
    )

    private fun insertAudit(values: List<Any?>) {
        jdbc.update(
            "INSERT INTO audit_log (id, actor_type, actor_admin_id, action, entity_type, entity_id, " +
                "before_data, after_data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
            *values.toTypedArray(),
        )
    }

    private fun assertSqlState(expected: String, operation: () -> Unit) {
        val failure = assertFailsWith<DataAccessException> { operation() }
        val sqlException = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first()
        assertEquals(expected, sqlException.sqlState)
    }
}
