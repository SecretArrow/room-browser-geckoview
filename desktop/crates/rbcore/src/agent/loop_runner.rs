//! AgentLoop — the turn engine. Ports the exact contracts:
//! * stream → tool calls → execute → append tool results → repeat;
//! * MAX_CONTINUE_NUDGES = 2 plain answers over an unfinished plan go back
//!   to work; the third ends the turn with the completion notice;
//! * history trimmed to system + newest 40 messages;
//! * step limit reached → one final request WITHOUT tools;
//! * tool results capped at 6000 chars.

use crate::agent::gateway::{parse_tool_blocks, render_prompt_tools, AgentGateway, StreamPart, TurnRequest};
use crate::agent::plan::AgentPlan;
use crate::agent::{AgentConfig, AgentEvent, ChatMessage, ToolCall, MAX_CONTINUE_NUDGES, MAX_HISTORY, MAX_TOOL_RESULT_CHARS};
use serde_json::Value;

/// What the loop needs from its host to execute tools and surface UI.
pub trait LoopHost {
    /// Executes one gated tool call; returns the result text.
    fn execute_tool(&mut self, call: &ToolCall) -> String;
    /// Interactive confirm callback (Confirm-actions / destructive gates).
    fn confirm(&mut self, prompt: &str) -> bool {
        let _ = prompt;
        true
    }
    fn emit(&mut self, event: AgentEvent);
}

pub struct LoopOutcome {
    pub final_answer: String,
    pub messages: Vec<ChatMessage>,
    pub plan: AgentPlan,
    pub steps_used: usize,
}

/// Accumulates streamed parts into complete tool calls (deltas concat).
pub fn collect_parts(parts: &[StreamPart]) -> (String, Vec<ToolCall>) {
    let mut text = String::new();
    let mut thinking = String::new();
    let mut order: Vec<String> = Vec::new();
    let mut args_acc: std::collections::BTreeMap<String, String> = std::collections::BTreeMap::new();
    let mut names: std::collections::BTreeMap<String, String> = std::collections::BTreeMap::new();
    for p in parts {
        match p {
            StreamPart::Text(t) => text.push_str(t),
            StreamPart::Thinking(t) => thinking.push_str(t),
            StreamPart::ToolCall { id, name, arguments } => {
                let key = if id.is_empty() { "toolu_0".to_string() } else { id.clone() };
                if !order.contains(&key) {
                    order.push(key.clone());
                }
                if !name.is_empty() {
                    names.insert(key.clone(), name.clone());
                }
                let acc = args_acc.entry(key).or_default();
                acc.push_str(arguments);
            }
            StreamPart::Done => {}
        }
    }
    let _ = thinking;
    let mut calls = Vec::new();
    for id in order {
        let name = names.get(&id).cloned().unwrap_or_default();
        let raw = args_acc.get(&id).cloned().unwrap_or_default();
        let arguments: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
        calls.push(ToolCall { id, name, arguments });
    }
    (text, calls)
}

/// Runs one turn to completion.
pub fn run_turn<G: AgentGateway, H: LoopHost>(
    gateway: &mut G,
    host: &mut H,
    config: &AgentConfig,
    plan: &mut AgentPlan,
    history: &mut Vec<ChatMessage>,
) -> crate::CoreResult<LoopOutcome> {
    let mut steps_used = 0usize;
    let mut nudges_used = 0usize;
    let tools = crate::agent::tool_defs();
    let use_native = gateway.supports_native_tools();

    loop {
        if steps_used >= config.steps {
            // Step limit: force a plain answer without tools.
            let req = TurnRequest {
                messages: trim_history(history),
                tools: Vec::new(),
                temperature: config.temperature,
                max_tokens: 1024,
            };
            let parts = gateway.stream_turn(&req)?;
            let (text, _) = collect_parts(&parts);
            host.emit(AgentEvent::FinalAnswer(text.clone()));
            return Ok(LoopOutcome { final_answer: text, messages: history.clone(), plan: plan.clone(), steps_used });
        }
        steps_used += 1;

        // Render messages; providers without native tools get the prompt form.
        let mut msgs = trim_history(history);
        if !use_native {
            if let Some(first) = msgs.first_mut() {
                if first.role == "system" {
                    first.content.push_str("\n\n");
                    first.content.push_str(&render_prompt_tools(&tools));
                }
            }
        }
        let req = TurnRequest {
            messages: msgs,
            tools: tools.clone(),
            temperature: config.temperature,
            max_tokens: 2048,
        };
        let parts = gateway.stream_turn(&req)?;
        let (mut text, mut calls) = collect_parts(&parts);
        if !use_native {
            // Prompt-tool providers embed calls in the text.
            let (parsed, rest) = parse_tool_blocks(&text);
            calls = parsed;
            text = rest;
        }
        if !text.is_empty() {
            host.emit(AgentEvent::AssistantText(text.clone()));
        }

        if calls.is_empty() {
            // Plain answer over an unfinished plan → nudge back to work
            // (bounded), exactly like MAX_CONTINUE_NUDGES.
            if !plan.is_empty() && !plan.unfinished().is_empty() && nudges_used < MAX_CONTINUE_NUDGES {
                nudges_used += 1;
                host.emit(AgentEvent::Notice(format!(
                    "The assistant answered before finishing the task list ({}/{} nudges used).",
                    nudges_used, MAX_CONTINUE_NUDGES
                )));
                history.push(ChatMessage::assistant(&text));
                history.push(ChatMessage::user(AgentPlan::continue_nudge()));
                continue;
            }
            if !plan.is_empty() && !plan.unfinished().is_empty() && nudges_used >= MAX_CONTINUE_NUDGES {
                let notice = plan.completion_notice();
                let mut final_text = text.clone();
                final_text.push_str("\n\n");
                final_text.push_str(&notice);
                host.emit(AgentEvent::FinalAnswer(final_text.clone()));
                history.push(ChatMessage::assistant(&final_text));
                return Ok(LoopOutcome { final_answer: final_text, messages: history.clone(), plan: plan.clone(), steps_used });
            }
            host.emit(AgentEvent::FinalAnswer(text.clone()));
            history.push(ChatMessage::assistant(&text));
            return Ok(LoopOutcome { final_answer: text, messages: history.clone(), plan: plan.clone(), steps_used });
        }

        // Record the assistant message with its tool calls.
        history.push(ChatMessage {
            role: "assistant".into(),
            content: text,
            tool_calls: calls.clone(),
            tool_call_id: String::new(),
            tool_name: String::new(),
        });

        for call in &calls {
            host.emit(AgentEvent::ToolStarted { name: call.name.clone(), args: call.arguments.clone() });
            let mut result = host.execute_tool(call);
            if result.chars().count() > MAX_TOOL_RESULT_CHARS {
                let cut: String = result.chars().take(MAX_TOOL_RESULT_CHARS).collect();
                result = format!("{cut}\n…(truncated)");
            }
            host.emit(AgentEvent::ToolFinished { name: call.name.clone(), result: result.clone() });
            // The plan tool updates the live plan.
            if call.name == "plan" {
                *plan = AgentPlan::from_json(&call.arguments);
                plan.activate_first();
                host.emit(AgentEvent::PlanUpdated(plan.to_json()));
                result = "Plan updated.".to_string();
            }
            history.push(ChatMessage {
                role: "tool".into(),
                content: result,
                tool_calls: Vec::new(),
                tool_call_id: call.id.clone(),
                tool_name: call.name.clone(),
            });
        }
    }
}

/// system + newest MAX_HISTORY messages.
pub fn trim_history(history: &[ChatMessage]) -> Vec<ChatMessage> {
    let mut out: Vec<ChatMessage> = history
        .iter()
        .filter(|m| m.role == "system")
        .cloned()
        .collect();
    let tail: Vec<ChatMessage> = history
        .iter()
        .filter(|m| m.role != "system")
        .rev()
        .take(MAX_HISTORY)
        .cloned()
        .collect();
    out.extend(tail.into_iter().rev());
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::ChatMessage;
    use serde_json::json;
    use std::cell::RefCell;

    /// A scripted gateway: replies with fixed stream batches.
    struct Scripted {
        replies: Vec<Vec<StreamPart>>,
        index: usize,
        native: bool,
    }

    impl AgentGateway for Scripted {
        fn id(&self) -> &'static str {
            "scripted"
        }
        fn supports_native_tools(&self) -> bool {
            self.native
        }
        fn supports_vision(&self) -> bool {
            true
        }
        fn stream_turn(&mut self, _req: &TurnRequest) -> crate::CoreResult<Vec<StreamPart>> {
            let i = self.index.min(self.replies.len() - 1);
            self.index += 1;
            Ok(self.replies[i].clone())
        }
        fn list_models(&self) -> crate::CoreResult<Vec<String>> {
            Ok(vec!["scripted-model".into()])
        }
    }

    struct MockHost {
        events: RefCell<Vec<String>>,
        results: std::collections::HashMap<String, String>,
    }

    impl MockHost {
        fn new() -> Self {
            MockHost { events: RefCell::new(Vec::new()), results: Default::default() }
        }
    }

    impl LoopHost for MockHost {
        fn execute_tool(&mut self, call: &ToolCall) -> String {
            if call.name == "plan" {
                return "Plan updated.".into();
            }
            self.results.get(&call.name).cloned().unwrap_or_else(|| format!("ok: {}", call.name))
        }
        fn emit(&mut self, event: AgentEvent) {
            let s = match event {
                AgentEvent::AssistantText(t) => format!("text:{t}"),
                AgentEvent::FinalAnswer(t) => format!("final:{t}"),
                AgentEvent::Notice(t) => format!("notice:{t}"),
                AgentEvent::ToolStarted { name, .. } => format!("start:{name}"),
                AgentEvent::ToolFinished { name, .. } => format!("finish:{name}"),
                AgentEvent::PlanUpdated(_) => "plan-updated".into(),
                AgentEvent::AgentError(t) => format!("error:{t}"),
                AgentEvent::AssistantThinking(t) => format!("thinking:{t}"),
            };
            self.events.borrow_mut().push(s);
        }
    }

    fn tc(name: &str, args: Value) -> StreamPart {
        StreamPart::ToolCall { id: format!("call_{}", name), name: name.into(), arguments: serde_json::to_string(&args).unwrap() }
    }

    #[test]
    fn simple_text_turn() {
        let mut g = Scripted { replies: vec![vec![StreamPart::Text("done".into())]], index: 0, native: true };
        let mut host = MockHost::new();
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("hi")];
        let out = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        assert_eq!(out.final_answer, "done");
        assert_eq!(out.steps_used, 1);
    }

    #[test]
    fn tool_calls_execute_and_results_flow_back() {
        let mut g = Scripted {
            replies: vec![
                vec![tc("read_page", json!({}))],
                vec![StreamPart::Text("all done".into())],
            ],
            index: 0,
            native: true,
        };
        let mut host = MockHost::new();
        host.results.insert("read_page".into(), "PAGE CONTENT".into());
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let out = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        assert_eq!(out.final_answer, "all done");
        // history: sys, user, assistant(tool), tool, assistant
        assert_eq!(history.len(), 5);
        assert_eq!(history[3].role, "tool");
        assert_eq!(history[3].content, "PAGE CONTENT");
        assert_eq!(history[3].tool_name, "read_page");
        let events = host.events.borrow();
        assert!(events.contains(&"start:read_page".to_string()));
        assert!(events.contains(&"finish:read_page".to_string()));
    }

    #[test]
    fn plain_answers_over_unfinished_plan_are_nudged_then_ended() {
        // The model answers plainly three times over an unfinished plan:
        // nudge, nudge, then the completion notice ends the turn.
        let mut g = Scripted {
            replies: vec![vec![StreamPart::Text("partial".into())]],
            index: 0,
            native: true,
        };
        let mut host = MockHost::new();
        let mut plan = AgentPlan::from_json(&json!({"items": [{"text": "a", "status": "pending"}]}));
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let out = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        assert!(out.final_answer.contains("partial"));
        assert!(out.final_answer.contains("still unfinished"));
        let events = host.events.borrow();
        let notices = events.iter().filter(|e| e.starts_with("notice:")).count();
        assert_eq!(notices, 2); // two nudges (1/2 and 2/2)
        assert_eq!(out.steps_used, 3);
        // the nudges went back into the history as user messages
        let user_msgs = history.iter().filter(|m| m.role == "user").count();
        assert_eq!(user_msgs, 3); // original + 2 nudges
    }

    #[test]
    fn plan_tool_replaces_the_plan_and_activates() {
        let mut g = Scripted {
            replies: vec![
                vec![tc("plan", json!({"items": [{"text": "one", "status": "pending"}, {"text": "two", "status": "pending"}]}))],
                vec![tc("plan", json!({"items": [{"text": "one", "status": "done"}, {"text": "two", "status": "done"}]}))],
                vec![StreamPart::Text("finished all".into())],
            ],
            index: 0,
            native: true,
        };
        let mut host = MockHost::new();
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let out = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        assert_eq!(out.final_answer, "finished all");
        assert_eq!(plan.items.len(), 2);
        assert_eq!(plan.items[0].status, "done");
        assert_eq!(plan.items[1].status, "done");
        let events = host.events.borrow();
        assert_eq!(events.iter().filter(|e| *e == "plan-updated").count(), 2);
    }

    #[test]
    fn step_limit_forces_a_plain_answer() {
        // A gateway that always calls tools + a 2-step budget.
        let mut g = Scripted {
            replies: vec![vec![tc("read_page", json!({}))]],
            index: 0,
            native: true,
        };
        g.replies.push(vec![StreamPart::Text("forced final".into())]);
        let mut host = MockHost::new();
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let cfg = AgentConfig { steps: 2, ..Default::default() };
        let out = run_turn(&mut g, &mut host, &cfg, &mut plan, &mut history).unwrap();
        assert_eq!(out.final_answer, "forced final");
        assert_eq!(out.steps_used, 2);
    }

    #[test]
    fn prompt_tool_providers_get_calls_from_text() {
        let mut g = Scripted {
            replies: vec![
                vec![StreamPart::Text("Working.\n```tool\n{\"name\":\"read_page\",\"arguments\":{}}\n```".into())],
                vec![StreamPart::Text("done here".into())],
            ],
            index: 0,
            native: false,
        };
        let mut host = MockHost::new();
        host.results.insert("read_page".into(), "CONTENT".into());
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let out = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        assert_eq!(out.final_answer, "done here");
        assert_eq!(history[3].role, "tool");
        assert_eq!(history[3].content, "CONTENT");
    }

    #[test]
    fn history_trims_to_system_plus_40() {
        let mut history = vec![ChatMessage::system("sys")];
        for i in 0..60 {
            history.push(ChatMessage::user(&format!("m{i}")));
        }
        let trimmed = trim_history(&history);
        assert_eq!(trimmed.len(), 41);
        assert_eq!(trimmed[0].role, "system");
        assert_eq!(trimmed[1].content, "m20"); // newest 40 = m20..m59
        assert_eq!(trimmed[40].content, "m59");
    }

    #[test]
    fn tool_results_are_capped() {
        let long = "x".repeat(7000);
        let mut g = Scripted {
            replies: vec![
                vec![tc("read_page", json!({}))],
                vec![StreamPart::Text("done".into())],
            ],
            index: 0,
            native: true,
        };
        let mut host = MockHost::new();
        host.results.insert("read_page".into(), long);
        let mut plan = AgentPlan::default();
        let mut history = vec![ChatMessage::system("sys"), ChatMessage::user("go")];
        let _ = run_turn(&mut g, &mut host, &AgentConfig::default(), &mut plan, &mut history).unwrap();
        let tool_msg = &history[3];
        assert!(tool_msg.content.chars().count() < 6100);
        assert!(tool_msg.content.contains("(truncated)"));
    }
}
