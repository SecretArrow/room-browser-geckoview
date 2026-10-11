package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Maps [ChatMessage.images] into a provider's own request shape. The
 * [OI]-compatible transport is built here (pure, so it is covered by JVM
 * tests); Anthropic's block shape lives in [AnthropicMessages] and Ollama's
 * `images` array on [OllamaChatMessage]. Providers that cannot carry images
 * ([AgentVisionSupport]) are expected to be refused by the caller before a
 * request is built at all.
 */
object AgentVisionRequest {

    /**
     * The `POST {base}/chat/completions` body. With no images anywhere this is
     * exactly the plain [ChatRequest] serialization; a message that carries
     * images turns its `content` into a text + `image_url` parts array.
     */
    fun openAiBody(request: ChatRequest): String {
        if (request.messages.none { it.images != null }) {
            return AgentJson.encodeToString(ChatRequest.serializer(), request)
        }
        // Patch the already-encoded request rather than re-serialize it, so
        // every field that is NOT content keeps the exact shape it has today.
        // An empty list is stripped too: the plain "images" key is not [OI].
        val root = AgentJson.encodeToJsonElement(ChatRequest.serializer(), request).jsonObject
        val encoded = root.getValue("messages").jsonArray
        val messages = buildJsonArray {
            request.messages.forEachIndexed { index, message ->
                val images = message.images.orEmpty()
                add(
                    JsonObject(
                        LinkedHashMap(encoded[index].jsonObject).apply {
                            remove("images")
                            if (images.isNotEmpty()) {
                                put("content", contentParts(message.content, images))
                            }
                        }
                    )
                )
            }
        }
        val body = JsonObject(LinkedHashMap(root).apply { put("messages", messages) })
        return AgentJson.encodeToString(JsonObject.serializer(), body)
    }

    private fun contentParts(text: String?, images: List<AgentImage>): JsonArray = buildJsonArray {
        text?.takeIf { it.isNotEmpty() }?.let { value ->
            addJsonObject {
                put("type", "text")
                put("text", value)
            }
        }
        images.forEach { image ->
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", dataUrl(image)) }
            }
        }
    }

    private fun dataUrl(image: AgentImage): String =
        "data:${image.mediaType};base64,${image.dataBase64}"
}
