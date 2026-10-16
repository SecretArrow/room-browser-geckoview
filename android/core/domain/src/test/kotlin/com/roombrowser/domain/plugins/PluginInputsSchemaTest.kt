package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The `inputs` schema contract, rule by rule — the one schema whose whole
 * job is to describe a dialog this build renders, so every field shape,
 * cap and member is held by a test that breaks exactly one thing. The
 * safe-pattern gate gets its own attention: a form that never finishes
 * validating is worse than a pattern an author has to simplify.
 */
class PluginInputsSchemaTest {

    @Test
    fun `the design's own example schema is valid`() {
        assertThat(PluginInputsSchema.validate(AgentJson.parseToJsonElement(EXAMPLE).jsonArray)).isEmpty()
    }

    @Test
    fun `absence is no schema, and neither is a non-array`() {
        assertThat(PluginInputsSchema.validate(null)).isEmpty()
        assertThat(PluginInputsSchema.validate(AgentJson.parseToJsonElement("[]").jsonArray)).isEmpty()
        assertThat(PluginInputsSchema.validate(AgentJson.parseToJsonElement("{}").jsonObject)).isNotEmpty()
    }

    @Test
    fun `at most twelve fields are allowed`() {
        val many = (1..13).joinToString(",", "[", "]") {
            """{"key":"k$it","type":"string","title":"T$it"}"""
        }
        val errors = PluginInputsSchema.validate(AgentJson.parseToJsonElement(many).jsonArray)
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("at most 12")
    }

    @Test
    fun `keys follow the lowercase identifier grammar and are unique`() {
        listOf("", "Email", "1k", "k-x", "k${"y".repeat(32)}").forEach { bad ->
            assertThat(
                PluginInputsSchema.validate(field("""{"key":"$bad","type":"string","title":"T"}"""))
            ).isNotEmpty()
        }
        assertThat(
            PluginInputsSchema.validate(
                AgentJson.parseToJsonElement(
                    """[{"key":"email","type":"string","title":"T"},
                        {"key":"email","type":"string","title":"T"}]"""
                ).jsonArray
            )
        ).isNotEmpty()
    }

    @Test
    fun `a type is one of the five`() {
        val errors = PluginInputsSchema.validate(field("""{"key":"k","type":"text","title":"T"}"""))
        assertThat(errors.single()).contains("one of")
        assertThat(PluginInputsSchema.TYPES)
            .containsExactly("string", "number", "boolean", "choice", "secret")
    }

    @Test
    fun `a title is required within its cap`() {
        assertThat(PluginInputsSchema.validate(field("""{"key":"k","type":"string"}"""))).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"${"t".repeat(65)}"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T"}"""))
        ).isEmpty()
    }

    @Test
    fun `unknown members inside a field are refused, in both formats`() {
        val errors = PluginInputsSchema.validate(
            field("""{"key":"k","type":"string","title":"T","wigets":1}""")
        )
        assertThat(errors.single()).contains("unknown field \"wigets\"")
        assertThat(errors.single()).contains("refused rather than ignored")
    }

    @Test
    fun `a description has a cap and a required must be a boolean`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","description":"${"d".repeat(201)}"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","required":"yes"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","required":true}"""))
        ).isEmpty()
    }

    @Test
    fun `a placeholder belongs to string, number and secret fields, within its cap`() {
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"a"}],"placeholder":"pick"}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"string","title":"T","placeholder":"${"p".repeat(101)}"}""")
            )
        ).isNotEmpty()
        listOf("string", "number", "secret").forEach { type ->
            assertThat(
                PluginInputsSchema.validate(field("""{"key":"k","type":"$type","title":"T","placeholder":"P"}"""))
            ).isEmpty()
        }
    }

    @Test
    fun `a pattern must compile, stay in budget, and not nest quantifiers`() {
        // The design's own example is a pattern a form can run.
        assertThat(PluginInputsSchema.patternProblem("^[^@]+@[^@]+$")).isNull()
        assertThat(PluginInputsSchema.patternProblem("^\\d{4}-\\d{2}$")).isNull()
        assertThat(PluginInputsSchema.patternProblem("(a|b)+")).isNull()
        assertThat(PluginInputsSchema.patternProblem("[a-z]+")).isNull()
        // Nested quantifiers are the catastrophic shape, refused conservatively.
        assertThat(PluginInputsSchema.patternProblem("(a+)+")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(a*)*")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(.*)+")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(a{2,})+")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(?:a+)+")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(\\d+x)+")).isNotNull()
        // Something that does not compile is not a pattern at all.
        assertThat(PluginInputsSchema.patternProblem("[")).isNotNull()
        assertThat(PluginInputsSchema.patternProblem("(")).isNotNull()
    }

    @Test
    fun `a pattern belongs to string and secret fields, within its cap`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"number","title":"T","pattern":"\\d+"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","pattern":"${"a".repeat(201)}"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"secret","title":"T","pattern":"^\\S+$"}"""))
        ).isEmpty()
    }

    @Test
    fun `text bounds belong to text fields, must be integers, and must be sane`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"number","title":"T","minLength":1}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","minLength":1.5}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","minLength":-1}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"string","title":"T","minLength":5,"maxLength":2}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"string","title":"T","minLength":3,"maxLength":200}""")
            )
        ).isEmpty()
    }

    @Test
    fun `numeric bounds belong to number fields, and must be sane`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","min":1}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"number","title":"T","min":"1"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"number","title":"T","min":100,"max":10}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"number","title":"T","min":1,"max":100}""")
            )
        ).isEmpty()
    }

    @Test
    fun `options belong to choices, are capped, unique and labelled`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","options":[{"value":"a"}]}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"choice","title":"T"}"""))
        ).isNotEmpty()
        val thirteen = (1..13).joinToString(",", "[", "]") { """{"value":"v$it"}""" }
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"choice","title":"T","options":$thirteen}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"a"},{"value":"a"}]}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"${"v".repeat(65)}"}]}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"a","label":"A"}]}""")
            )
        ).isEmpty()
    }

    @Test
    fun `a default must match its field's type`() {
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"number","title":"T","default":"hi"}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"boolean","title":"T","default":1}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","default":3}"""))
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(field("""{"key":"k","type":"string","title":"T","default":null}"""))
        ).isNotEmpty()
        // A choice's default has to be one of the choices.
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"a"}],"default":"b"}""")
            )
        ).isNotEmpty()
        assertThat(
            PluginInputsSchema.validate(
                field("""{"key":"k","type":"choice","title":"T","options":[{"value":"a"}],"default":"a"}""")
            )
        ).isEmpty()
    }

    @Test
    fun `parse is tolerant and round-trips through its own json`() {
        val schema = PluginInputsSchema.parse(AgentJson.parseToJsonElement(EXAMPLE).jsonArray)
        assertThat(schema.fields).hasSize(1)
        val email = schema.fields.single()
        assertThat(email.key).isEqualTo("email")
        assertThat(email.type).isEqualTo("string")
        assertThat(email.title).isEqualTo("Email")
        assertThat(email.required).isTrue()
        assertThat(email.pattern).isEqualTo("^[^@]+@[^@]+$")
        assertThat(email.patternError).isEqualTo("Enter a valid email")
        assertThat(email.minLength).isEqualTo(3)
        assertThat(email.maxLength).isEqualTo(200)

        val roundTripped = PluginInputsSchema.parse(
            AgentJson.parseToJsonElement(schema.toJson().toString()).jsonArray
        )
        assertThat(roundTripped).isEqualTo(schema)
        assertThat(schema.toJsonOrNull()).isNotNull()
    }

    @Test
    fun `a schema with no fields stores as no schema at all`() {
        assertThat(PluginInputsSchema(emptyList()).toJsonOrNull()).isNull()
        assertThat(PluginInputsSchema.parse(null).toJsonOrNull()).isNull()
        assertThat(PluginInputsSchema.parse(AgentJson.parseToJsonElement("[]").jsonArray).fields)
            .isEmpty()
    }

    // ------------------------------------------------------------ fixtures

    private fun field(body: String): JsonArray =
        AgentJson.parseToJsonElement("[$body]").jsonArray

    private companion object {
        val EXAMPLE = """
            [{"key":"email","type":"string","title":"Email",
              "description":"Where the digest goes","required":true,
              "placeholder":"you@example.com",
              "pattern":"^[^@]+@[^@]+$","patternError":"Enter a valid email",
              "minLength":3,"maxLength":200}]
        """.trimIndent()
    }
}
