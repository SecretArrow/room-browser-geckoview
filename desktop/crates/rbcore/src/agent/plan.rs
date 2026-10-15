//! Agent plan (todos) — port of `AgentPlan`: status transitions, the
//! continue-nudge text and the completion notice.

use serde_json::{json, Value};

#[derive(Debug, Clone)]
pub struct PlanItem {
    pub text: String,
    pub status: String, // pending | in_progress | done
}

#[derive(Debug, Clone, Default)]
pub struct AgentPlan {
    pub items: Vec<PlanItem>,
    pub refusal_count: usize,
    pub nudge_count: usize,
    pub continue_nudges: usize,
}

impl AgentPlan {
    pub fn from_json(v: &Value) -> AgentPlan {
        let mut p = AgentPlan::default();
        if let Some(arr) = v.get("items").and_then(|x| x.as_array()) {
            for it in arr {
                p.items.push(PlanItem {
                    text: it.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string(),
                    status: it
                        .get("status")
                        .and_then(|s| s.as_str())
                        .unwrap_or("pending")
                        .to_string(),
                });
            }
        }
        p
    }

    pub fn to_json(&self) -> Value {
        json!({"items": self.items.iter().map(|i| json!({"text": i.text, "status": i.status})).collect::<Vec<_>>()})
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn unfinished(&self) -> Vec<&PlanItem> {
        self.items.iter().filter(|i| i.status != "done").collect()
    }

    pub fn has_in_progress(&self) -> bool {
        self.items.iter().any(|i| i.status == "in_progress")
    }

    /// All pending → the first becomes in_progress (the Android transition).
    pub fn activate_first(&mut self) {
        if self.has_in_progress() {
            return;
        }
        if let Some(first) = self.items.iter_mut().find(|i| i.status == "pending") {
            first.status = "in_progress".into();
        }
    }

    /// Marks the in_progress item done and activates the next pending one.
    pub fn complete_current(&mut self) {
        if let Some(cur) = self.items.iter_mut().find(|i| i.status == "in_progress") {
            cur.status = "done".into();
        }
        self.activate_first();
    }

    /// `AgentPlan.NUDGE` — sent back to the model when it answers plainly
    /// over an unfinished plan.
    pub fn continue_nudge() -> &'static str {
        "The task list still has unfinished items. Continue working through \
         them with the tools available; answer only when every item is done."
    }

    /// `AgentPlan.completionNotice` — the notice that ends the turn after
    /// the nudge budget is spent.
    pub fn completion_notice(&self) -> String {
        let remaining = self.unfinished().len();
        format!(
            "The assistant stopped answering while {remaining} task item(s) were still unfinished. \
             You can send another message to continue."
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan_with(items: &[(&str, &str)]) -> AgentPlan {
        AgentPlan::from_json(&json!({"items": items.iter().map(|(t, s)| json!({"text": t, "status": s})).collect::<Vec<_>>()}))
    }

    #[test]
    fn transitions_activate_and_complete() {
        let mut p = plan_with(&[("a", "pending"), ("b", "pending")]);
        p.activate_first();
        assert_eq!(p.items[0].status, "in_progress");
        p.complete_current();
        assert_eq!(p.items[0].status, "done");
        assert_eq!(p.items[1].status, "in_progress");
        p.complete_current();
        assert!(p.unfinished().is_empty());
    }

    #[test]
    fn json_roundtrip_and_notices() {
        let p = plan_with(&[("a", "done"), ("b", "pending")]);
        let back = AgentPlan::from_json(&p.to_json());
        assert_eq!(back.items.len(), 2);
        assert_eq!(back.unfinished().len(), 1);
        assert!(AgentPlan::continue_nudge().contains("unfinished items"));
        assert!(p.completion_notice().contains("1 task item"));
    }
}
