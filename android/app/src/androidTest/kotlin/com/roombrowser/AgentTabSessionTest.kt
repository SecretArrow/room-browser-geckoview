package com.roombrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.repo.AgentRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-tab agent chats (Room v10) — the queries that decide WHICH conversation
 * a tab shows.
 *
 * This is the part of per-tab chats whose failure is silent: a lookup that
 * falls back to the wrong row does not crash, it just continues somebody
 * else's conversation, and the only trace is a chat that suddenly has history
 * it never had. The three ways that can happen are pinned here — a tab
 * resolving to another tab's chat, a detached chat resolving to a tab it no
 * longer belongs to, and a chat leaking across profiles.
 *
 * The migration itself (ALTER TABLE + CREATE INDEX, both additive) is not
 * exercised here: the database under test is created fresh at v10, so no
 * migration runs. A fresh install never runs one either; the migration path
 * only exists for an in-place upgrade.
 *
 * Every test ends on a VOID-returning Truth call, because a Kotlin method
 * whose last expression has a value is non-void and JUnit4 then refuses the
 * whole class (see the runBlocking<Unit> note in the project memory).
 *
 * The names are snake_case identifiers, NOT backticked sentences. `runBlocking`
 * makes each body a suspend lambda, which Kotlin compiles to a synthetic class
 * named after the method — and a method name is free to contain spaces while a
 * CLASS name is not: D8 rejects "Space characters in SimpleName ... are not
 * allowed prior to DEX version 040", and with minSdk 28 that is every build.
 * The failure lands in dexBuilderDebugAndroidTest, so it is androidTest-only
 * and invisible to `quality`; AndroidTestNamingTest in the unit-test source set
 * is what turns it into a fast failure instead.
 */
@RunWith(AndroidJUnit4::class)
class AgentTabSessionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AgentRepository

    private val profileA = "11111111-1111-1111-1111-111111111111"
    private val profileB = "22222222-2222-2222-2222-222222222222"
    private val tabOne = "aaaaaaaa-1111-1111-1111-111111111111"
    private val tabTwo = "bbbbbbbb-2222-2222-2222-222222222222"

    /** A tab id that is the SAME in both profiles, to prove profile scoping. */
    private val sharedTab = "cccccccc-3333-3333-3333-333333333333"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AgentRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun each_tab_resolves_to_its_own_conversation() = runBlocking<Unit> {
        val first = repo.createSession(profileA, "about cats", 1, "m", tabId = tabOne)
        val second = repo.createSession(profileA, "about dogs", 1, "m", tabId = tabTwo)

        assertThat(repo.sessionForTab(profileA, tabOne)?.id).isEqualTo(first)
        assertThat(repo.sessionForTab(profileA, tabTwo)?.id).isEqualTo(second)
        // A third tab has no conversation of its own — it must NOT be handed
        // one of the two above, which is the whole point of the lookup.
        assertThat(repo.sessionForTab(profileA, sharedTab)).isNull()
    }

    @Test
    fun a_detached_chat_keeps_its_history_but_no_longer_owns_the_tab() = runBlocking<Unit> {
        val id = repo.createSession(profileA, "old chat", 1, "m", tabId = tabOne)
        repo.addMessage(id, "user", "hello")

        // What the "+" button does: the tab gives the chat up so the next
        // message starts a new one.
        repo.bindSessionToTab(id, "")

        assertThat(repo.sessionForTab(profileA, tabOne)).isNull()
        // Detached is not deleted: it is still the profile's history.
        assertThat(repo.sessions(profileA).map { it.id }).contains(id)
        assertThat(repo.messages(id)).hasSize(1)
    }

    @Test
    fun adopting_a_chat_moves_it_off_the_tab_that_had_it() = runBlocking<Unit> {
        val id = repo.createSession(profileA, "moving", 1, "m", tabId = tabOne)
        // What the history list does on tap: bind it to the tab on screen.
        repo.bindSessionToTab(id, tabTwo)

        assertThat(repo.sessionForTab(profileA, tabTwo)?.id).isEqualTo(id)
        assertThat(repo.sessionForTab(profileA, tabOne)).isNull()
    }

    @Test
    fun the_same_tab_id_in_two_profiles_is_two_different_conversations() = runBlocking<Unit> {
        val a = repo.createSession(profileA, "personal", 1, "m", tabId = sharedTab)
        val b = repo.createSession(profileB, "work", 1, "m", tabId = sharedTab)

        assertThat(repo.sessionForTab(profileA, sharedTab)?.id).isEqualTo(a)
        assertThat(repo.sessionForTab(profileB, sharedTab)?.id).isEqualTo(b)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun a_blank_tab_id_never_resolves_to_an_unbound_chat() = runBlocking<Unit> {
        // Unbound chats all share tab_id = "": a caller with no tab must not
        // be handed one of them as though it owned it.
        repo.createSession(profileA, "no tab", 1, "m")

        assertThat(repo.sessionForTab(profileA, "")).isNull()
        assertThat(repo.sessions(profileA)).hasSize(1)
    }
}
