package com.roombrowser.domain.model

/** Search engine presets. Per-profile selection is supported. */
data class SearchEngine(
    val id: String,
    val label: String,
    val searchUrlTemplate: String, // {query} placeholder
    val suggestionUrlTemplate: String? = null
)

object SearchEngines {
    val all: List<SearchEngine> = listOf(
        SearchEngine(
            id = "google",
            label = "Google",
            searchUrlTemplate = "https://www.google.com/search?q={query}",
            suggestionUrlTemplate = "https://www.google.com/complete/search?client=firefox&q={query}"
        ),
        SearchEngine(
            id = "bing",
            label = "Bing",
            searchUrlTemplate = "https://www.bing.com/search?q={query}"
        ),
        SearchEngine(
            id = "duckduckgo",
            label = "DuckDuckGo",
            searchUrlTemplate = "https://duckduckgo.com/?q={query}",
            suggestionUrlTemplate = "https://duckduckgo.com/ac/?q={query}&type=list"
        ),
        SearchEngine(
            id = "brave",
            label = "Brave Search",
            searchUrlTemplate = "https://search.brave.com/search?q={query}"
        ),
        SearchEngine(
            id = "startpage",
            label = "Startpage",
            searchUrlTemplate = "https://www.startpage.com/sp/search?query={query}"
        ),
        SearchEngine(
            id = "ecosia",
            label = "Ecosia",
            searchUrlTemplate = "https://www.ecosia.org/search?q={query}"
        )
    )

    fun byId(id: String): SearchEngine =
        all.firstOrNull { it.id == id } ?: all.first { it.id == "google" }

    fun buildSearchUrl(engineId: String, query: String): String =
        byId(engineId).searchUrlTemplate.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
}
