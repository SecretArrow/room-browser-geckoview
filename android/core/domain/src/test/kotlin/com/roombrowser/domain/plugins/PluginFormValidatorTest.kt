package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The answer side of the input form, rule by rule: every message the matrix
 * in the design produces is held here exactly, because the empty map is the
 * ONLY thing that unlocks the Allow-and-Run button — a message that stops
 * matching its rule is a gate that opens (or stays shut) for the wrong
 * reason.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then runs
 * none of the class.
 */
class PluginFormValidatorTest {

    /** The five field types at once — the schema a real module would declare. */
    private fun schema(body: String = SCHEMA): PluginInputsSchema =
        PluginInputsSchema.parse(AgentJson.parseToJsonElement(body).jsonArray)

    /** An answers object from raw JSON — the editor's wire shape. */
    private fun values(body: String): JsonObject =
        AgentJson.parseToJsonElement(body).jsonObject

    @Test
    fun `a complete answer to every field type validates clean`() {
        val errors = PluginFormValidator.validate(
            schema(),
            values(
                """
                {"email":"you@example.com","count":42,"verified":true,
                 "cadence":"daily","password":"hunter2"}
                """.trimIndent()
            )
        )
        assertThat(errors).isEmpty()
    }

    @Test
    fun `a required field left blank is required by its title`() {
        // Blank means the empty string AND the absent key alike — the dialog
        // sends both spellings depending on how its editors clear.
        listOf(
            """{"email":"","count":42,"verified":true,"cadence":"daily","password":"hunter2"}""",
            """{"count":42,"verified":true,"cadence":"daily","password":"hunter2"}""",
            """{"email":"   ","count":42,"verified":true,"cadence":"daily","password":"hunter2"}"""
        ).forEach { body ->
            val errors = PluginFormValidator.validate(schema(), values(body))
            assertThat(errors).containsEntry("email", "Email is required")
        }
    }

    @Test
    fun `an optional field left blank is simply absent`() {
        // cadence is optional (no required, no default): leaving it out is an
        // answer the form accepts, not a gap to report.
        val errors = PluginFormValidator.validate(
            schema(),
            values("""{"email":"you@example.com","count":42,"verified":true,"password":"hunter2"}""")
        )
        assertThat(errors).doesNotContainKey("cadence")
        assertThat(errors).isEmpty()
    }

    @Test
    fun `a blank secret with a stored value means keep the old one`() {
        val existing = values("""{"password":"hunter2"}""")
        listOf(
            """{"email":"you@example.com","count":1,"verified":true,"cadence":"daily"}""",
            """{"email":"you@example.com","count":1,"verified":true,"cadence":"daily","password":""}""",
            """{"email":"you@example.com","count":1,"verified":true,"cadence":"daily","password":"  "}"""
        ).forEach { body ->
            val errors = PluginFormValidator.validate(schema(), values(body), existing)
            assertThat(errors).doesNotContainKey("password")
        }
        // …and the normalized answer keeps the stored value, not the blank.
        assertThat(
            PluginFormValidator.normalizedValues(
                schema(),
                values("""{"email":"you@example.com","count":1,"verified":true,"cadence":"daily","password":""}"""),
                existing
            )["password"].toString()
        ).isEqualTo("\"hunter2\"")
    }

    @Test
    fun `a blank secret with nothing stored is a gap like any other`() {
        // The form showed no "leave blank to keep" line — there is nothing to
        // keep — so a required secret reports required, and an optional one
        // stays quiet. The optional half needs its own fixture: the SCHEMA's
        // password is required, and an absent required field would (rightly)
        // report itself.
        val required = PluginFormValidator.validate(
            schema(),
            values("""{"email":"you@example.com","count":1,"verified":true,"cadence":"daily","password":""}""")
        )
        assertThat(required).containsEntry("password", "Password is required")
        val optional = PluginFormValidator.validate(
            schema(SECRET_OPTIONAL),
            values("""{"email":"you@example.com","count":1,"verified":true,"cadence":"daily"}""")
        )
        assertThat(optional).doesNotContainKey("password")
    }

    @Test
    fun `a pattern failure names the pattern's own error`() {
        val errors = PluginFormValidator.validate(
            schema(),
            values("""{"email":"not-an-email","count":1,"verified":true,"cadence":"daily","password":"hunter2"}""")
        )
        assertThat(errors).containsEntry("email", "Enter a valid email")
    }

    @Test
    fun `a pattern failure without a patternError says invalid format`() {
        val unlabelled = schema(
            """[{"key":"email","type":"string","title":"Email","required":true,
                "pattern":"^[^@]+@[^@]+$"}]"""
        )
        val errors = PluginFormValidator.validate(unlabelled, values("""{"email":"nope"}"""))
        assertThat(errors).containsEntry("email", "Email has an invalid format")
    }

    @Test
    fun `string bounds speak in characters`() {
        val bounds = schema(
            """[{"key":"name","type":"string","title":"Name","minLength":3,"maxLength":8}]"""
        )
        assertThat(PluginFormValidator.validate(bounds, values("""{"name":"ab"}""")))
            .containsEntry("name", "Name must be at least 3 characters")
        assertThat(PluginFormValidator.validate(bounds, values("""{"name":"123456789"}""")))
            .containsEntry("name", "Name must be at most 8 characters")
        // In bounds is in bounds, at both edges.
        assertThat(PluginFormValidator.validate(bounds, values("""{"name":"abc"}"""))).isEmpty()
        assertThat(PluginFormValidator.validate(bounds, values("""{"name":"12345678"}"""))).isEmpty()
    }

    @Test
    fun `a number must parse and be finite`() {
        val number = schema("""[{"key":"count","type":"number","title":"Count"}]""")
        listOf("abc", "NaN", "Infinity", "-Infinity", "1e999").forEach { bad ->
            assertThat(PluginFormValidator.validate(number, values("""{"count":"$bad"}""")))
                .containsEntry("count", "Count must be a number")
        }
        // A real number that arrives as a JSON number, not a string, parses
        // the same way — the editor's wire is allowed either spelling.
        assertThat(PluginFormValidator.validate(number, values("""{"count":42}"""))).isEmpty()
        assertThat(PluginFormValidator.validate(number, values("""{"count":"42.5"}"""))).isEmpty()
    }

    @Test
    fun `number bounds speak without a trailing point zero`() {
        val range = schema("""[{"key":"count","type":"number","title":"Count","min":1,"max":100}]""")
        assertThat(PluginFormValidator.validate(range, values("""{"count":0}""")))
            .containsEntry("count", "Count must be at least 1")
        assertThat(PluginFormValidator.validate(range, values("""{"count":101}""")))
            .containsEntry("count", "Count must be at most 100")
        assertThat(PluginFormValidator.validate(range, values("""{"count":1}"""))).isEmpty()
        assertThat(PluginFormValidator.validate(range, values("""{"count":100}"""))).isEmpty()
        // A fractional bound renders as the fraction, not a truncated whole.
        val fractional = schema("""[{"key":"grade","type":"number","title":"Grade","min":0.5}]""")
        assertThat(PluginFormValidator.validate(fractional, values("""{"grade":0.1}""")))
            .containsEntry("grade", "Grade must be at least 0.5")
    }

    @Test
    fun `a boolean is true or false and nothing else`() {
        val flag = schema("""[{"key":"verified","type":"boolean","title":"Verified"}]""")
        assertThat(PluginFormValidator.validate(flag, values("""{"verified":"yes"}""")))
            .containsEntry("verified", "Verified must be true or false")
        assertThat(PluginFormValidator.validate(flag, values("""{"verified":1}""")))
            .containsEntry("verified", "Verified must be true or false")
        assertThat(PluginFormValidator.validate(flag, values("""{"verified":true}"""))).isEmpty()
        assertThat(PluginFormValidator.validate(flag, values("""{"verified":false}"""))).isEmpty()
    }

    @Test
    fun `a choice must be one of the listed options`() {
        val errors = PluginFormValidator.validate(
            schema(),
            values("""{"email":"you@example.com","count":1,"verified":true,"cadence":"hourly","password":"hunter2"}""")
        )
        assertThat(errors).containsEntry("cadence", "Cadence must be one of: daily, weekly")
        // The listed values are the right ones, option by option.
        assertThat(PluginFormValidator.validate(schema(), values("""{"email":"you@example.com","count":1,"verified":true,"cadence":"weekly","password":"hunter2"}""")))
            .isEmpty()
    }

    @Test
    fun `a value that is not a plain primitive is a broken wire`() {
        val text = schema("""[{"key":"email","type":"string","title":"Email"}]""")
        assertThat(PluginFormValidator.validate(text, values("""{"email":{"nested":1}}""")))
            .containsEntry("email", "Email has an invalid format")
        assertThat(PluginFormValidator.validate(text, values("""{"email":["a","b"]}""")))
            .containsEntry("email", "Email has an invalid format")
    }

    @Test
    fun `one error per field, and only the first thing wrong`() {
        // "no" breaks both the minLength and the pattern; the row shows the
        // length complaint, because that is the thing to fix first.
        val errors = PluginFormValidator.validate(
            schema(),
            values("""{"email":"no","count":0,"verified":"yes","cadence":"never","password":""}""")
        )
        assertThat(errors).containsExactlyEntriesIn(
            mapOf(
                "email" to "Email must be at least 3 characters",
                "count" to "Count must be at least 1",
                "verified" to "Verified must be true or false",
                "cadence" to "Cadence must be one of: daily, weekly",
                "password" to "Password is required"
            )
        )
        // Schema order is the order the rows turn red in.
        assertThat(errors.keys).containsExactly("email", "count", "verified", "cadence", "password").inOrder()
    }

    @Test
    fun `normalized values keep what was entered and fill declared defaults`() {
        val normalized = PluginFormValidator.normalizedValues(
            schema(),
            values("""{"email":"you@example.com","count":42,"cadence":"weekly","password":"hunter2","rogue":"dropped"}""")
        )
        assertThat(normalized["email"].toString()).isEqualTo("\"you@example.com\"")
        assertThat(normalized["count"].toString()).isEqualTo("42")
        // Untouched optional field: its declared default stands in.
        assertThat(normalized["verified"].toString()).isEqualTo("true")
        assertThat(normalized["cadence"].toString()).isEqualTo("\"weekly\"")
        assertThat(normalized["password"].toString()).isEqualTo("\"hunter2\"")
        // A key the schema does not know never survives normalization.
        assertThat(normalized.keys).containsExactly("email", "count", "verified", "cadence", "password")
    }

    // ------------------------------------------------------------ fixtures

    private companion object {
        val SCHEMA = """
            [{"key":"email","type":"string","title":"Email","required":true,
              "pattern":"^[^@]+@[^@]+$","patternError":"Enter a valid email",
              "minLength":3,"maxLength":200},
             {"key":"count","type":"number","title":"Count","required":false,
              "min":1,"max":100,"default":7},
             {"key":"verified","type":"boolean","title":"Verified","default":true},
             {"key":"cadence","type":"choice","title":"Cadence",
              "options":[{"value":"daily","label":"Daily"},{"value":"weekly","label":"Weekly"}]},
             {"key":"password","type":"secret","title":"Password","required":true,
              "minLength":6}]
        """.trimIndent()

        /** The same form with the secret OPTIONAL — the quiet half of the
         * blank-secret test: an optional field left out is not a gap. */
        val SECRET_OPTIONAL = SCHEMA.replace(
            """"password","type":"secret","title":"Password","required":true,""",
            """"password","type":"secret","title":"Password","""
        )
    }
}
