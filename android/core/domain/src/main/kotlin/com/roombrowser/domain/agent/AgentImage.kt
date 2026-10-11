package com.roombrowser.domain.agent

import kotlinx.serialization.Serializable

/**
 * A screenshot a turn wants the model to look at. Carried outside
 * [ChatMessage.content] on purpose, so "this provider cannot see images" is a
 * per-gateway answer ([AgentVisionSupport]) rather than a serialization error.
 * [dataBase64] never carries a `data:` prefix here; each gateway adds one.
 */
@Serializable
data class AgentImage(
    val dataBase64: String,
    val mediaType: String,
    val width: Int,
    val height: Int
)
