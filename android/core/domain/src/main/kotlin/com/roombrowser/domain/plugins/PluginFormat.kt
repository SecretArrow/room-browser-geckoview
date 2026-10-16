package com.roombrowser.domain.plugins

/**
 * The two manifest formats a plugin row can carry.
 *
 * Everything else about a row is format-blind — one table, one installer,
 * one runtime — so this one string is the whole distinction: it decides
 * which wording the model and the user hear ("plugin" vs "module"), which
 * permission vocabulary the row may declare (`files` exists only for
 * modules), and which sandbox globals the host builds for a call.
 *
 * `plugin` is the spelling every row written before this field existed
 * already is, which is why it is the column's default.
 */
object PluginFormat {

    /** plugin.json — this app's own manifest format. */
    const val PLUGIN = "plugin"

    /** module.json — the ZAgents module format, mapped onto the same row. */
    const val ZAGENTS = "zagents"
}
