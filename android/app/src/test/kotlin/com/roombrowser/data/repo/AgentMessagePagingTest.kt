package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentDao
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AppDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The keyset paging the agent's chat surfaces read through
 * ([AgentRepository.messagePage] and [AgentRepository.allMessages]).
 *
 * The DAO seam is a mockk whose one stub reimplements the SQL it will carry
 * (id < before, newest first, LIMIT) over a hand-written row list — the shape
 * the repository tests already use, because what is under test here is the
 * REPOSITORY's own arithmetic: the +1 probe that makes hasOlder exact, the
 * reversal into transcript order, and the loop that re-ascends a whole chat
 * without losing the row that proved there was one more page.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class AgentMessagePagingTest {

    @Test
    fun `the first window is the chat's newest rows, oldest-first, and knows more exist`() =
        runBlocking<Unit> {
            val repo = repoWith(rows(1..5))
            val page = repo.messagePage(1L, Long.MAX_VALUE, limit = 3)
            assertThat(page.rows.map { it.id }).containsExactly(3L, 4L, 5L).inOrder()
            assertThat(page.hasOlder).isTrue()
        }

    @Test
    fun `a chat shorter than the window is whole and says nothing is older`() =
        runBlocking<Unit> {
            val repo = repoWith(rows(1..2))
            val page = repo.messagePage(1L, Long.MAX_VALUE, limit = 3)
            assertThat(page.rows.map { it.id }).containsExactly(1L, 2L).inOrder()
            assertThat(page.hasOlder).isFalse()
        }

    @Test
    fun `a chat of exactly the window size says nothing is older`() = runBlocking<Unit> {
        // The +1 probe is the whole difference between "exactly fits" and
        // "one more than fits": offering a load-older that comes back empty
        // is a button that lies.
        val repo = repoWith(rows(1..3))
        val page = repo.messagePage(1L, Long.MAX_VALUE, limit = 3)
        assertThat(page.rows.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(page.hasOlder).isFalse()
    }

    @Test
    fun `an older window pages below the oldest loaded row`() = runBlocking<Unit> {
        val repo = repoWith(rows(1..5))
        val page = repo.messagePage(1L, beforeId = 3L, limit = 3)
        assertThat(page.rows.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(page.hasOlder).isFalse()
    }

    @Test
    fun `a window past the start of the chat is empty`() = runBlocking<Unit> {
        val repo = repoWith(rows(1..5))
        val page = repo.messagePage(1L, beforeId = 1L, limit = 3)
        assertThat(page.rows).isEmpty()
        assertThat(page.hasOlder).isFalse()
    }

    @Test
    fun `allMessages re-ascends every page of a chat without losing a row`() =
        runBlocking<Unit> {
            // 7 rows through a 3-row window: pages come back newest-window
            // first, and only the loop's re-ascension turns them into one
            // transcript — the export's full-fidelity promise.
            val repo = repoWith(rows(1..7))
            assertThat(repo.allMessages(1L, pageSize = 3).map { it.id })
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L).inOrder()
        }

    @Test
    fun `allMessages on a chat that fits one page is that page`() = runBlocking<Unit> {
        val repo = repoWith(rows(1..3))
        assertThat(repo.allMessages(1L, pageSize = 3).map { it.id })
            .containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `allMessages on an empty chat answers empty`() = runBlocking<Unit> {
        val repo = repoWith(rows(1..0))
        assertThat(repo.allMessages(1L, pageSize = 3)).isEmpty()
    }

    @Test
    fun `hasUserMessage answers the DAO's probe`() = runBlocking<Unit> {
        // The staged-activation question must stay exact at any chat length,
        // which is exactly why it is an EXISTS probe and not a window read.
        assertThat(repoWith(listOf(row(1L, "user"))).hasUserMessage(1L)).isTrue()
        assertThat(repoWith(listOf(row(1L, "assistant"))).hasUserMessage(1L)).isFalse()
        assertThat(repoWith(emptyList()).hasUserMessage(1L)).isFalse()
    }

    @Test
    fun `messages remains the full oldest-first read the diagnostics and tests lean on`() =
        runBlocking<Unit> {
            val all = rows(1..4)
            assertThat(repoWith(all).messages(1L).map { it.id })
                .containsExactly(1L, 2L, 3L, 4L).inOrder()
        }

    // ------------------------------------------------------------ fixtures

    /** Rows 1..n, one assistant row each, ids in insert order. */
    private fun rows(ids: IntRange): List<AgentMessageEntity> =
        ids.map { row(it.toLong(), "assistant") }

    private fun row(id: Long, role: String) = AgentMessageEntity(
        id = id,
        sessionId = 1L,
        role = role,
        content = "row $id",
        createdAt = id
    )

    /**
     * The DAO as its SQL behaves, over one chat's rows: below the cursor,
     * newest first, capped — plus the two reads the repository still passes
     * straight through.
     */
    private fun repoWith(all: List<AgentMessageEntity>): AgentRepository {
        val dao = mockk<AgentDao> {
            coEvery { messagesBefore(any(), any(), any()) } answers {
                val before = secondArg<Long>()
                val limit = thirdArg<Int>()
                all.filter { it.id < before }
                    .sortedByDescending { it.id }
                    .take(limit)
            }
            coEvery { hasUserMessage(any()) } answers { all.any { it.role == "user" } }
            coEvery { messages(any()) } returns all.sortedBy { it.id }
        }
        val db = mockk<AppDatabase> {
            every { agentDao() } returns dao
        }
        return AgentRepository(db)
    }
}
