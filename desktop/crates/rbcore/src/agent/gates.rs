//! The layered tool gates — ports of PlanModeToolGate, AgentPermissionGate,
//! ActionBudgetGate and ScheduledRunToolGate (plus the headless-chat gate).

use crate::agent::{INTERACTIVE_TOOLS, MAX_ACTIONS_PER_TOOL, MAX_ACTIONS_PER_TURN, IDENTICAL_ACTION_MIN_INTERVAL_MS, MAX_CONSECUTIVE_FAILURES};
use serde_json::Value;

/// Plan-mode refusal list — these NEVER run while planning.
const PLAN_REFUSED: &[&str] = &[
    "click", "fill_input", "press_enter", "select_option", "press_keys",
    "run_js", "auto_like", "auto_repost", "auto_reply", "auto_post",
    "wallet_approve", "wallet_switch_network", "devtools_patch",
    "download_file", "save_pdf", "node_run", "node_packages",
];

/// The named agent permissions (ProfileSettings.agentWithheldPermissions
/// is a deny-list over these).
pub const AGENT_PERMISSIONS: &[&str] = &[
    "devtools.read", "devtools.network", "devtools.storage", "devtools.cookies",
    "devtools.security", "agent.exportDiagnostics", "agent.compute", "agent.node",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunKind {
    /// Normal interactive chat (ASK / YOLO modes).
    Interactive,
    /// Headless chat turn (no visible tab).
    HeadlessChat,
    /// Scheduled AI task run.
    ScheduledRun,
}

/// Everything the gates need to decide.
#[derive(Debug, Clone, Default)]
pub struct GateContext {
    pub plan_mode: bool,
    pub withheld_permissions: Vec<String>,
    pub run_kind: RunKind,
    /// AiTaskPermissions defaults: read/navigate/interact yes, post no.
    pub task_allow_post: bool,
    /// ProfileSettings.taskProfileTools hands app_2fa/app_notes (minus delete)
    /// to scheduled runs.
    pub task_profile_tools: bool,
}

impl Default for RunKind {
    fn default() -> Self {
        RunKind::Interactive
    }
}

/// One combined verdict — the gates are decorators on Android; keeping them
/// as pure functions with the SAME refusal messages preserves the contract.
pub fn gate(
    ctx: &GateContext,
    tool: &str,
    args: &Value,
    budget: &mut ActionBudget,
) -> Result<(), String> {
    // 1. Plan mode.
    if ctx.plan_mode {
        if PLAN_REFUSED.contains(&tool) {
            return Err(format!(
                "Refused in plan mode: {tool} only runs after you approve the plan."
            ));
        }
        let action = args.get("action").and_then(|a| a.as_str()).unwrap_or("");
        if tool == "app_2fa" && action == "fill" {
            return Err("Refused in plan mode: filling a 2FA code needs the plan approved first.".into());
        }
        if tool == "app_shields" && (action == "toggle" || action == "clear_site_data") {
            return Err("Refused in plan mode: changing shields needs the plan approved first.".into());
        }
        if tool == "app_site_permission" && action == "set" {
            return Err("Refused in plan mode: changing site permissions needs the plan approved first.".into());
        }
    }

    // 2. Scheduled runs and headless chats refuse whole tool families.
    if ctx.run_kind == RunKind::ScheduledRun {
        let action = args.get("action").and_then(|a| a.as_str()).unwrap_or("");
        let tab_tools = [
            "navigate", "search_web", "click", "fill_input", "press_enter", "scroll",
            "go_back", "forward", "open_new_tab", "switch_tab", "close_tab",
            "select_option", "press_keys", "see_page", "compare_screenshots",
            "app_page", "devtools_patch", "download_file", "save_pdf", "auto_like",
            "auto_repost", "auto_reply", "auto_post",
        ];
        if tab_tools.contains(&tool) {
            return Err(format!("Refused for scheduled runs: {tool} would move the visible browser."));
        }
        let family = tool.split('_').next().unwrap_or("");
        if matches!(family, "wallet" | "devtools" | "node")
            || tool == "run_js"
            || tool == "node_packages"
            || (tool == "app_2fa" && !ctx.task_profile_tools)
            || (tool == "app_notes" && !ctx.task_profile_tools)
        {
            return Err(format!("Refused for scheduled runs: {tool} is not enabled for unattended runs."));
        }
        if tool == "app_notes" && action == "delete" {
            return Err("Refused for scheduled runs: app_notes/delete is always manual.".into());
        }
        if tool == "app_data" && matches!(action, "clear" | "remove" | "cancel") {
            return Err("Refused for scheduled runs: destructive data actions are always manual.".into());
        }
    }
    if ctx.run_kind == RunKind::HeadlessChat {
        if tool.starts_with("wallet_") || tool == "app_page" {
            return Err(format!("Refused in a headless chat: {tool} needs a visible page."));
        }
        if crate::agent::DEVTOOLS_TOOLS.contains(&tool) {
            return Err(format!("Refused in a headless chat: {tool} needs a bound tab."));
        }
        if tool == "app_2fa" && args.get("action").and_then(|a| a.as_str()) == Some("fill") {
            return Err("Refused in a headless chat: filling a 2FA code needs a visible page.".into());
        }
    }

    // 3. Permission deny-list.
    if tool.starts_with("devtools_") {
        if ctx.withheld_permissions.iter().any(|p| p == "devtools.read") {
            return Err("devtools tools are withheld for this profile.".into());
        }
        if tool == "devtools_network" && ctx.withheld_permissions.iter().any(|p| p == "devtools.network") {
            return Err("devtools.network is withheld for this profile.".into());
        }
    }
    if tool == "compute_js" && ctx.withheld_permissions.iter().any(|p| p == "agent.compute") {
        return Err("agent.compute is withheld for this profile.".into());
    }
    if (tool == "node_run" || tool == "node_packages") && ctx.withheld_permissions.iter().any(|p| p == "agent.node") {
        return Err("agent.node is withheld for this profile.".into());
    }
    if tool == "devtools_report" && ctx.withheld_permissions.iter().any(|p| p == "agent.exportDiagnostics") {
        return Err("agent.exportDiagnostics is withheld for this profile.".into());
    }

    // 4. Action budget (interactive runs only; identical-action throttling).
    if ctx.run_kind == RunKind::Interactive && INTERACTIVE_TOOLS.contains(&tool) {
        budget.record_action(tool, args)?;
    }
    Ok(())
}

/// Port of `ActionBudgetTracker`.
#[derive(Debug, Clone, Default)]
pub struct ActionBudget {
    actions_this_turn: usize,
    actions_per_tool: std::collections::HashMap<String, usize>,
    last_action: Option<(String, String, i64)>, // (tool, args-json, time)
    consecutive_failures: usize,
    page_digest: String,
}

impl ActionBudget {
    pub fn record_action(&mut self, tool: &str, args: &Value) -> Result<(), String> {
        if self.actions_this_turn >= MAX_ACTIONS_PER_TURN {
            return Err(format!("Action budget exhausted: at most {MAX_ACTIONS_PER_TURN} actions per turn."));
        }
        let per = self.actions_per_tool.entry(tool.to_string()).or_insert(0);
        if *per >= MAX_ACTIONS_PER_TOOL {
            return Err(format!("Action budget exhausted: at most {MAX_ACTIONS_PER_TOOL} {tool} actions per turn."));
        }
        let args_json = serde_json::to_string(args).unwrap_or_default();
        if let Some((last_tool, last_args, _)) = &self.last_action {
            if last_tool == tool && last_args == &args_json {
                return Err("The same action was already tried; the page has not changed since. Try a different approach.".into());
            }
        }
        self.actions_this_turn += 1;
        *self.actions_per_tool.entry(tool.to_string()).or_insert(0) += 1;
        self.last_action = Some((tool.to_string(), args_json, crate::now_ms()));
        Ok(())
    }

    pub fn note_result(&mut self, ok: bool) {
        if ok {
            self.consecutive_failures = 0;
        } else {
            self.consecutive_failures += 1;
        }
    }

    pub fn consecutive_failures(&self) -> usize {
        self.consecutive_failures
    }

    /// The page digest gate: same action + unchanged page is refused before
    /// it runs (the digest is taken from the last read_page/navigate).
    pub fn set_page_digest(&mut self, digest: &str) {
        self.page_digest = digest.to_string();
    }

    pub fn page_digest(&self) -> &str {
        &self.page_digest
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn args(v: Value) -> Value {
        v
    }

    #[test]
    fn plan_mode_refusals_match_the_policy() {
        let mut ctx = GateContext { plan_mode: true, ..Default::default() };
        let mut b = ActionBudget::default();
        for tool in ["click", "fill_input", "run_js", "wallet_approve", "node_run", "devtools_patch"] {
            let r = gate(&ctx, tool, &args(json!({})), &mut b);
            assert!(r.is_err(), "{tool} must be refused in plan mode");
        }
        // reading is fine while planning
        assert!(gate(&ctx, "read_page", &args(json!({})), &mut b).is_ok());
        assert!(gate(&ctx, "list_tabs", &args(json!({})), &mut b).is_ok());
        // per-action refusals
        assert!(gate(&ctx, "app_2fa", &args(json!({"action": "fill"})), &mut b).is_err());
        assert!(gate(&ctx, "app_shields", &args(json!({"action": "toggle"})), &mut b).is_err());
        assert!(gate(&ctx, "app_notes", &args(json!({"action": "add"})), &mut b).is_ok());
        ctx.plan_mode = false;
        assert!(gate(&ctx, "click", &args(json!({"ref": 3})), &mut b).is_ok());
    }

    #[test]
    fn scheduled_runs_refuse_whole_families() {
        let ctx = GateContext { run_kind: RunKind::ScheduledRun, ..Default::default() };
        let mut b = ActionBudget::default();
        for tool in ["click", "navigate", "wallet_state", "devtools_console", "node_run", "run_js", "see_page"] {
            assert!(gate(&ctx, tool, &args(json!({})), &mut b).is_err(), "{tool} refused for scheduled runs");
        }
        // read_page + app_notes (with the setting) are allowed
        assert!(gate(&ctx, "read_page", &args(json!({})), &mut b).is_ok());
        let ctx2 = GateContext { run_kind: RunKind::ScheduledRun, task_profile_tools: true, ..Default::default() };
        assert!(gate(&ctx2, "app_notes", &args(json!({"action": "add"})), &mut b).is_ok());
        assert!(gate(&ctx2, "app_notes", &args(json!({"action": "delete"})), &mut b).is_err());
    }

    #[test]
    fn permission_deny_list() {
        let ctx = GateContext {
            withheld_permissions: vec!["agent.node".into(), "devtools.read".into()],
            ..Default::default()
        };
        let mut b = ActionBudget::default();
        assert!(gate(&ctx, "node_run", &args(json!({})), &mut b).is_err());
        assert!(gate(&ctx, "devtools_console", &args(json!({})), &mut b).is_err());
        assert!(gate(&ctx, "read_page", &args(json!({})), &mut b).is_ok());
    }

    #[test]
    fn budget_limits_and_identical_actions() {
        let ctx = GateContext::default();
        let mut b = ActionBudget::default();
        for i in 0..15 {
            assert!(gate(&ctx, "click", &args(json!({"ref": i})), &mut b).is_ok());
        }
        // the 16th click of the turn exceeds the per-tool cap (15)
        assert!(gate(&ctx, "click", &args(json!({"ref": 99})), &mut b).is_err());
        // identical args are refused regardless of the caps
        assert!(b.record_action("fill_input", &json!({"ref": 1, "text": "x"})).is_ok());
        assert!(b.record_action("fill_input", &json!({"ref": 1, "text": "x"})).is_err());
        // different args pass
        assert!(b.record_action("fill_input", &json!({"ref": 2, "text": "x"})).is_ok());
    }

    #[test]
    fn turn_budget_across_tools() {
        // 60 actions per turn, 15 per tool: four tools fill the turn.
        let ctx = GateContext::default();
        let mut b = ActionBudget::default();
        for tool in ["click", "fill_input", "auto_like", "auto_repost"] {
            for i in 0..15 {
                assert!(gate(&ctx, tool, &args(json!({"ref": i})), &mut b).is_ok(), "{tool} #{i}");
            }
        }
        // the turn budget is now exhausted for every interactive tool
        assert!(gate(&ctx, "app_tabs", &args(json!({"action": "list"})), &mut b).is_err());
        // non-interactive tools are never budgeted
        assert!(gate(&ctx, "read_page", &args(json!({})), &mut b).is_ok());
    }
}
