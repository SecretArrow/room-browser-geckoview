package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The agent's step list is the only thing a long run shows the user about
 * itself, so what it does with a bad call matters: a rejected list must leave
 * the previous one standing, and an accepted one must be the WHOLE list the
 * model wrote rather than a merge.
 *
 * The parser is deliberately strict about the status word and lenient about
 * its spelling, which is the pair of tests below.
 */
class AgentPlanTest {

    private fun args(vararg steps: Pair<String, String>): String {
        val items = steps.joinToString(",") { (text, status) ->
            """{"text":"$text","status":"$status"}"""
        }
        return """{"items":[$items]}"""
    }

    @Test
    fun `a whole list comes back in the order it was sent`() {
        val parsed = AgentPlan.parse(
            args(
                "open the site" to "done",
                "sign in" to "in_progress",
                "download the invoice" to "pending"
            )
        )

        val ok = parsed as AgentPlan.Parsed.Ok
        assertThat(ok.items.map { it.text })
            .containsExactly("open the site", "sign in", "download the invoice").inOrder()
        assertThat(ok.items.map { it.status }).containsExactly(
            AgentPlan.Status.DONE,
            AgentPlan.Status.IN_PROGRESS,
            AgentPlan.Status.PENDING
        ).inOrder()
        // Void-returning last statement: an assertion that returns Ordered
        // makes the method non-void and JUnit4 then runs none of the class.
        assertThat(ok.items).hasSize(3)
    }

    @Test
    fun `the status word is read leniently but not guessed`() {
        val lenient = AgentPlan.parse(args("sign in" to " In_Progress "))
        assertThat((lenient as AgentPlan.Parsed.Ok).items.single().status)
            .isEqualTo(AgentPlan.Status.IN_PROGRESS)

        val unknown = AgentPlan.parse(args("sign in" to "halfway"))
        assertThat(unknown).isInstanceOf(AgentPlan.Parsed.Failure::class.java)
        // The refusal has to name the words that work, or the model repeats it.
        assertThat((unknown as AgentPlan.Parsed.Failure).reason)
            .contains("pending")
        assertThat(unknown.reason).contains("in_progress")
        assertThat(unknown.reason).contains("done")
    }

    @Test
    fun `an empty list is refused and says the call replaces rather than adds`() {
        val parsed = AgentPlan.parse("""{"items":[]}""")

        val failure = parsed as AgentPlan.Parsed.Failure
        assertThat(failure.reason).contains("whole list")
    }

    @Test
    fun `a step with no text is refused by its number`() {
        val parsed = AgentPlan.parse("""{"items":[{"text":"ok","status":"done"},{"text":"  ","status":"pending"}]}""")

        assertThat((parsed as AgentPlan.Parsed.Failure).reason).contains("step 2")
    }

    @Test
    fun `more steps than the cap is refused rather than silently cut`() {
        val tooMany = (1..AgentPlan.MAX_ITEMS + 1)
            .joinToString(",") { """{"text":"step $it","status":"pending"}""" }

        val parsed = AgentPlan.parse("""{"items":[$tooMany]}""")

        // Truncating would drop the step the model is actually on, so the call
        // fails and the previous list — the one the user is watching — stands.
        assertThat((parsed as AgentPlan.Parsed.Failure).reason).contains("${AgentPlan.MAX_ITEMS}")
    }

    @Test
    fun `one long step is clipped, not dropped`() {
        val long = "x".repeat(AgentPlan.MAX_TEXT_CHARS + 50)

        val ok = AgentPlan.parse(args(long to "pending")) as AgentPlan.Parsed.Ok

        assertThat(ok.items.single().text).hasLength(AgentPlan.MAX_TEXT_CHARS)
    }

    @Test
    fun `arguments that are not JSON fail rather than clearing the list`() {
        assertThat(AgentPlan.parse("not json at all")).isInstanceOf(AgentPlan.Parsed.Failure::class.java)
        assertThat(AgentPlan.parse("""{"items":"nope"}""")).isInstanceOf(AgentPlan.Parsed.Failure::class.java)
    }

    @Test
    fun `the result carries the list so the panel can show it without parsing again`() {
        val result = AgentPlan.execute(args("open the site" to "done", "sign in" to "in_progress"))

        assertThat(result.ok).isTrue()
        assertThat(result.plan.map { it.status }).containsExactly(
            AgentPlan.Status.DONE,
            AgentPlan.Status.IN_PROGRESS
        ).inOrder()
        // The model reads its own list back, counted, so it can see the shape
        // of the task without having to count the lines itself.
        assertThat(result.output).contains("1 of 2 done")
    }

    @Test
    fun `a failed call carries no list, so the previous one is left standing`() {
        val result = AgentPlan.execute("""{"items":[]}""")

        assertThat(result.ok).isFalse()
        assertThat(result.plan).isEmpty()
    }

    @Test
    fun `the rendered list marks every step`() {
        val rendered = AgentPlan.render(
            listOf(
                AgentPlan.Item("open the site", AgentPlan.Status.DONE),
                AgentPlan.Item("sign in", AgentPlan.Status.IN_PROGRESS),
                AgentPlan.Item("download the invoice", AgentPlan.Status.PENDING)
            )
        )

        assertThat(rendered).contains("[x] open the site")
        assertThat(rendered).contains("[>] sign in")
        assertThat(rendered).contains("[ ] download the invoice")
    }

    @Test
    fun `a stored list survives the round trip through the chat row`() {
        val items = listOf(
            AgentPlan.Item("open the site", AgentPlan.Status.DONE),
            AgentPlan.Item("sign in", AgentPlan.Status.IN_PROGRESS),
            AgentPlan.Item("download the invoice", AgentPlan.Status.PENDING)
        )

        val back = AgentPlan.decode(AgentPlan.encode(items))

        // Text and status both, in order: a reopened chat has to show the same
        // card it showed before, or the progress the user was watching is lost.
        assertThat(back).isEqualTo(items)
        // And it is the shape the TOOL takes, so a stored list can never be
        // something parse() would reject.
        assertThat(AgentPlan.parse(AgentPlan.encode(items))).isInstanceOf(AgentPlan.Parsed.Ok::class.java)
    }

    @Test
    fun `a chat with no list, or an unreadable one, opens with an empty list`() {
        assertThat(AgentPlan.decode(null)).isEmpty()
        assertThat(AgentPlan.decode("")).isEmpty()
        assertThat(AgentPlan.decode("   ")).isEmpty()
        assertThat(AgentPlan.decode("not json")).isEmpty()
        assertThat(AgentPlan.decode("""{"items":[]}""")).isEmpty()
    }
}
