//! Agent sessions + messages persistence (desktop JSONL variant of the
//! Android `agent_sessions` / `agent_messages` tables). Sessions bind to a
//! tab since v10; the plan rides on the session row.

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 64 * 1024;

#[derive(Debug, Clone)]
pub struct AgentSession {
    pub id: String,
    pub profile_id: String,
    pub tab_id: i64,
    pub title: String,
    pub provider_id: String,
    pub model: String,
    pub plan_json: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone)]
pub struct AgentMessage {
    pub id: i64,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub tool_name: String,
    pub tool_args: String,
    pub tool_result: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct AgentSessions {
    pub sessions: Vec<AgentSession>,
    pub messages: Vec<AgentMessage>,
    next_message_id: i64,
}

pub fn sessions_path(data: &Path, profile_id: &str) -> std::path::PathBuf {
    crate::paths::join(&crate::paths::profile_settings(data, profile_id), "agent_sessions.jsonl")
}
pub fn messages_path(data: &Path, profile_id: &str) -> std::path::PathBuf {
    crate::paths::join(&crate::paths::profile_settings(data, profile_id), "agent_messages.jsonl")
}

impl AgentSessions {
    pub fn load(data: &Path, profile_id: &str) -> AgentSessions {
        let mut s = AgentSessions { sessions: Vec::new(), messages: Vec::new(), next_message_id: 1 };
        for v in load_lines(&sessions_path(data, profile_id), LINE_CAP) {
            let id = str_field(&v, "id");
            if id.is_empty() {
                continue;
            }
            s.sessions.push(AgentSession {
                id,
                profile_id: str_field(&v, "profile_id"),
                tab_id: int_field(&v, "tab_id"),
                title: str_field(&v, "title"),
                provider_id: str_field(&v, "provider_id"),
                model: str_field(&v, "model"),
                plan_json: str_field(&v, "plan_json"),
                created_at: int_field(&v, "created_at"),
                updated_at: int_field(&v, "updated_at"),
            });
        }
        for v in load_lines(&messages_path(data, profile_id), LINE_CAP) {
            let sid = str_field(&v, "session_id");
            if sid.is_empty() {
                continue;
            }
            let id = int_field(&v, "id");
            s.next_message_id = s.next_message_id.max(id + 1);
            s.messages.push(AgentMessage {
                id,
                session_id: sid,
                role: str_field(&v, "role"),
                content: str_field(&v, "content"),
                tool_name: str_field(&v, "tool_name"),
                tool_args: str_field(&v, "tool_args"),
                tool_result: str_field(&v, "tool_result"),
                created_at: int_field(&v, "created_at"),
            });
        }
        s
    }

    pub fn save(&self, data: &Path, profile_id: &str) -> std::io::Result<()> {
        let sess: Vec<Value> = self
            .sessions
            .iter()
            .map(|x| {
                json!({
                    "id": x.id, "profile_id": x.profile_id, "tab_id": x.tab_id,
                    "title": x.title, "provider_id": x.provider_id, "model": x.model,
                    "plan_json": x.plan_json, "created_at": x.created_at, "updated_at": x.updated_at,
                })
            })
            .collect();
        let msgs: Vec<Value> = self
            .messages
            .iter()
            .map(|m| {
                json!({
                    "id": m.id, "session_id": m.session_id, "role": m.role,
                    "content": m.content, "tool_name": m.tool_name,
                    "tool_args": m.tool_args, "tool_result": m.tool_result,
                    "created_at": m.created_at,
                })
            })
            .collect();
        save_lines(&sessions_path(data, profile_id), &sess)?;
        save_lines(&messages_path(data, profile_id), &msgs)
    }

    pub fn open_session(&mut self, profile_id: &str, tab_id: i64, provider_id: &str, model: &str, now: i64) -> String {
        if let Some(existing) = self
            .sessions
            .iter_mut()
            .find(|s| s.profile_id == profile_id && s.tab_id == tab_id)
        {
            existing.updated_at = now;
            return existing.id.clone();
        }
        let id = uuid::Uuid::new_v4().to_string();
        self.sessions.push(AgentSession {
            id: id.clone(),
            profile_id: profile_id.to_string(),
            tab_id,
            title: "New chat".into(),
            provider_id: provider_id.to_string(),
            model: model.to_string(),
            plan_json: String::new(),
            created_at: now,
            updated_at: now,
        });
        id
    }

    pub fn set_session_plan(&mut self, session_id: &str, plan_json: &str) {
        if let Some(s) = self.sessions.iter_mut().find(|s| s.id == session_id) {
            s.plan_json = plan_json.to_string();
        }
    }

    pub fn append(&mut self, session_id: &str, role: &str, content: &str, tool_name: &str, tool_args: &str, tool_result: &str, now: i64) -> i64 {
        let id = self.next_message_id;
        self.next_message_id += 1;
        self.messages.push(AgentMessage {
            id,
            session_id: session_id.to_string(),
            role: role.to_string(),
            content: content.to_string(),
            tool_name: tool_name.to_string(),
            tool_args: tool_args.to_string(),
            tool_result: tool_result.to_string(),
            created_at: now,
        });
        if let Some(s) = self.sessions.iter_mut().find(|s| s.id == session_id) {
            s.updated_at = now;
            if s.title == "New chat" && role == "user" && !content.is_empty() {
                s.title = content.chars().take(48).collect();
            }
        }
        id
    }

    pub fn messages_of(&self, session_id: &str) -> Vec<&AgentMessage> {
        self.messages.iter().filter(|m| m.session_id == session_id).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("rb-agent-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn sessions_bind_to_tabs_and_roundtrip() {
        let d = tmp("rt");
        let mut s = AgentSessions::load(&d, "p1");
        let id = s.open_session("p1", 7, "prov1", "gpt-x", 1000);
        // opening again for the same tab reuses the session
        assert_eq!(s.open_session("p1", 7, "prov1", "gpt-x", 2000), id);
        s.append(&id, "user", "check the news", "", "", "", 1000);
        s.append(&id, "assistant", "", "read_page", "{}", "PAGE", 1100);
        s.set_session_plan(&id, "{\"items\":[]}");
        s.save(&d, "p1").unwrap();
        let back = AgentSessions::load(&d, "p1");
        assert_eq!(back.sessions.len(), 1);
        assert_eq!(back.sessions[0].tab_id, 7);
        assert_eq!(back.sessions[0].title, "check the news"); // derived from first user message
        assert_eq!(back.messages.len(), 2);
        assert_eq!(back.messages[1].tool_name, "read_page");
        assert_eq!(back.messages[1].tool_result, "PAGE");
        // message ids advance
        let mut s2 = back;
        let nid = s2.append(&id, "user", "more", "", "", "", 3000);
        assert_eq!(nid, 3);
        let _ = std::fs::remove_dir_all(&d);
    }
}
