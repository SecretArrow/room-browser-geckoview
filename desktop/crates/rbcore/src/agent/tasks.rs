//! Scheduled AI tasks — port of `ai_tasks` + `ScheduleMath` /
//! `AiTaskSchedulePlanner` (INTERVAL/DAILY/WEEKLY/MONTHLY with quiet hours,
/// catch-up exactly once, COMPLETED/DEFERRED/FAILED statuses).

use crate::jsonl::{int_field, load_lines, save_lines, str_field};
use serde_json::{json, Value};
use std::path::Path;

pub const LINE_CAP: usize = 16 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScheduleKind {
    Interval,
    Daily,
    Weekly,
    Monthly,
}

impl ScheduleKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ScheduleKind::Interval => "INTERVAL",
            ScheduleKind::Daily => "DAILY",
            ScheduleKind::Weekly => "WEEKLY",
            ScheduleKind::Monthly => "MONTHLY",
        }
    }
    pub fn parse(s: &str) -> ScheduleKind {
        match s {
            "DAILY" => ScheduleKind::Daily,
            "WEEKLY" => ScheduleKind::Weekly,
            "MONTHLY" => ScheduleKind::Monthly,
            _ => ScheduleKind::Interval,
        }
    }
}

/// The schedule JSON stored on the task row.
#[derive(Debug, Clone)]
pub struct Schedule {
    pub kind: ScheduleKind,
    /// INTERVAL: minutes between runs. DAILY/WEEKLY/MONTHLY: minute+hour of
    /// the day; WEEKLY adds day_of_week (0=Sunday), MONTHLY adds day_of_month.
    pub interval_minutes: i64,
    pub at_minute: i32,
    pub at_hour: i32,
    pub day_of_week: i32,
    pub day_of_month: i32,
    /// Quiet hours suppress firing (catch-up happens exactly once later).
    pub quiet_start_minute: i32,
    pub quiet_end_minute: i32,
    pub enabled: bool,
}

impl Default for Schedule {
    fn default() -> Self {
        Schedule {
            kind: ScheduleKind::Daily,
            interval_minutes: 60,
            at_minute: 0,
            at_hour: 9,
            day_of_week: 1,
            day_of_month: 1,
            quiet_start_minute: -1,
            quiet_end_minute: -1,
            enabled: true,
        }
    }
}

impl Schedule {
    pub fn from_json(v: &Value) -> Schedule {
        Schedule {
            kind: ScheduleKind::parse(&str_field(v, "kind")),
            interval_minutes: int_field(v, "interval_minutes").max(1),
            at_minute: int_field(v, "at_minute") as i32,
            at_hour: int_field(v, "at_hour") as i32,
            day_of_week: int_field(v, "day_of_week") as i32,
            day_of_month: int_field(v, "day_of_month") as i32,
            quiet_start_minute: int_field(v, "quiet_start_minute") as i32,
            quiet_end_minute: int_field(v, "quiet_end_minute") as i32,
            enabled: v.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true),
        }
    }

    pub fn to_json(&self) -> Value {
        json!({
            "kind": self.kind.as_str(), "interval_minutes": self.interval_minutes,
            "at_minute": self.at_minute, "at_hour": self.at_hour,
            "day_of_week": self.day_of_week, "day_of_month": self.day_of_month,
            "quiet_start_minute": self.quiet_start_minute, "quiet_end_minute": self.quiet_end_minute,
            "enabled": self.enabled,
        })
    }
}

#[derive(Debug, Clone)]
pub struct AiTask {
    pub id: String,
    pub name: String,
    pub prompt: String,
    pub profile_id: String,
    pub schedule: Schedule,
    pub provider_id: String,
    pub model: String,
    pub execution_mode: String, // STANDARD (hidden) | VISIBLE
    pub enabled: bool,
    pub last_run_at_ms: i64,
    pub last_run_status: String, // "" | COMPLETED | DEFERRED | FAILED
    pub last_result_summary: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Default)]
pub struct AiTasks {
    pub items: Vec<AiTask>,
}

pub fn tasks_path(data: &Path, profile_id: &str) -> std::path::PathBuf {
    crate::paths::join(&crate::paths::profile_settings(data, profile_id), "ai_tasks.jsonl")
}

impl AiTasks {
    pub fn load(data: &Path, profile_id: &str) -> AiTasks {
        let mut t = AiTasks::default();
        for v in load_lines(&tasks_path(data, profile_id), LINE_CAP) {
            let id = str_field(&v, "id");
            if id.is_empty() {
                continue;
            }
            t.items.push(AiTask {
                id,
                name: str_field(&v, "name"),
                prompt: str_field(&v, "prompt"),
                profile_id: str_field(&v, "profile_id"),
                schedule: Schedule::from_json(&v.get("schedule").cloned().unwrap_or(Value::Null)),
                provider_id: str_field(&v, "provider_id"),
                model: str_field(&v, "model"),
                execution_mode: str_field(&v, "execution_mode"),
                enabled: crate::jsonl::bool_field(&v, "enabled"),
                last_run_at_ms: int_field(&v, "last_run_at_ms"),
                last_run_status: str_field(&v, "last_run_status"),
                last_result_summary: str_field(&v, "last_result_summary"),
                created_at: int_field(&v, "created_at"),
            });
        }
        t
    }

    pub fn save(&self, data: &Path, profile_id: &str) -> std::io::Result<()> {
        let rows: Vec<Value> = self
            .items
            .iter()
            .map(|x| {
                json!({
                    "id": x.id, "name": x.name, "prompt": x.prompt,
                    "profile_id": x.profile_id, "schedule": x.schedule.to_json(),
                    "provider_id": x.provider_id, "model": x.model,
                    "execution_mode": x.execution_mode, "enabled": x.enabled,
                    "last_run_at_ms": x.last_run_at_ms, "last_run_status": x.last_run_status,
                    "last_result_summary": x.last_result_summary, "created_at": x.created_at,
                })
            })
            .collect();
        save_lines(&tasks_path(data, profile_id), &rows)
    }
}

/// Port of `ScheduleMath.next_run_at_ms`: the next due time strictly after
/// `now`, given the task's last run.
pub fn next_run_at_ms(task: &AiTask, now_ms: i64) -> i64 {
    use chrono::{Datelike, TimeZone, Timelike, Utc};
    let now = Utc.timestamp_millis_opt(now_ms).single().unwrap_or_else(Utc::now);
    match task.schedule.kind {
        ScheduleKind::Interval => {
            let step = task.schedule.interval_minutes * 60_000;
            let base = if task.last_run_at_ms > 0 { task.last_run_at_ms } else { now_ms };
            (base + step).max(now_ms + step)
        }
        ScheduleKind::Daily => {
            let mut candidate = now
                .with_hour(task.schedule.at_hour.max(0).min(23) as u32)
                .and_then(|d| d.with_minute(task.schedule.at_minute.max(0).min(59) as u32))
                .unwrap_or(now);
            if candidate <= now {
                candidate += chrono::Duration::days(1);
            }
            candidate.timestamp_millis()
        }
        ScheduleKind::Weekly => {
            // next occurrence of day_of_week at the given time
            let mut candidate = now
                .with_hour(task.schedule.at_hour.max(0).min(23) as u32)
                .and_then(|d| d.with_minute(task.schedule.at_minute.max(0).min(59) as u32))
                .unwrap_or(now);
            let wanted = task.schedule.day_of_week.max(0).min(6) as u32;
            let mut days = 0;
            while candidate.weekday().num_days_from_sunday() != wanted || candidate <= now {
                candidate += chrono::Duration::days(1);
                days += 1;
                if days > 8 {
                    break;
                }
            }
            candidate.timestamp_millis()
        }
        ScheduleKind::Monthly => {
            let dom = task.schedule.day_of_month.max(1).min(28) as u32;
            let candidate = now
                .with_day(dom)
                .and_then(|d| d.with_hour(task.schedule.at_hour.max(0).min(23) as u32))
                .and_then(|d| d.with_minute(task.schedule.at_minute.max(0).min(59) as u32))
                .unwrap_or(now);
            if candidate > now {
                return candidate.timestamp_millis();
            }
            // next month, same day-of-month (clamped to the 28th like the planner)
            let (y, m) = if now.month() == 12 { (now.year() + 1, 1) } else { (now.year(), now.month() + 1) };
            Utc.with_ymd_and_hms(y, m, dom, task.schedule.at_hour.max(0).min(23) as u32, task.schedule.at_minute.max(0).min(59) as u32, 0)
                .single()
                .map(|d| d.timestamp_millis())
                .unwrap_or_else(|| candidate.timestamp_millis())
        }
    }
}

/// Quiet hours: firing is suppressed inside the window; the run is DEFERRED
/// and catches up exactly once (the planner's contract).
pub fn in_quiet_hours(schedule: &Schedule, now_ms: i64) -> bool {
    if schedule.quiet_start_minute < 0 || schedule.quiet_end_minute < 0 {
        return false;
    }
    use chrono::{TimeZone, Timelike, Utc};
    let now = Utc.timestamp_millis_opt(now_ms).single().unwrap_or_else(Utc::now);
    let minute_of_day = now.hour() as i32 * 60 + now.minute() as i32;
    let (s, e) = (schedule.quiet_start_minute, schedule.quiet_end_minute);
    if s <= e {
        minute_of_day >= s && minute_of_day < e
    } else {
        minute_of_day >= s || minute_of_day < e // wraps midnight
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Datelike, TimeZone, Timelike, Utc};

    fn task(kind: ScheduleKind, last_run: i64) -> AiTask {
        AiTask {
            id: "t1".into(),
            name: "daily check".into(),
            prompt: "go".into(),
            profile_id: "p".into(),
            schedule: Schedule { kind, at_hour: 9, at_minute: 30, ..Default::default() },
            provider_id: "prov".into(),
            model: "m".into(),
            execution_mode: "STANDARD".into(),
            enabled: true,
            last_run_at_ms: last_run,
            last_run_status: String::new(),
            last_result_summary: String::new(),
            created_at: 0,
        }
    }

    #[test]
    fn interval_next_run() {
        // 2026-10-15 00:00:00 UTC = 1792521600000
        let now = 1_792_521_600_000i64;
        let t = task(ScheduleKind::Interval, now);
        assert_eq!(next_run_at_ms(&t, now), now + 60 * 60_000); // default 60 min
        // no last run → one interval from now
        let t2 = AiTask { last_run_at_ms: 0, ..t };
        assert_eq!(next_run_at_ms(&t2, now), now + 60 * 60_000);
    }

    #[test]
    fn daily_next_run_is_tomorrow_when_already_past() {
        let at_10 = Utc.with_ymd_and_hms(2026, 10, 15, 10, 0, 0).single().unwrap();
        let at_8 = Utc.with_ymd_and_hms(2026, 10, 15, 8, 0, 0).single().unwrap();
        let mut t = task(ScheduleKind::Daily, 0);
        t.schedule.at_hour = 9;
        t.schedule.at_minute = 30;
        // 10:00 is past 09:30 → tomorrow 09:30
        let next = next_run_at_ms(&t, at_10.timestamp_millis());
        let nd = Utc.timestamp_millis_opt(next).single().unwrap();
        assert_eq!((nd.year(), nd.month(), nd.day(), nd.hour(), nd.minute()), (2026, 10, 16, 9, 30));
        // 08:00 is before 09:30 → today
        let next2 = next_run_at_ms(&t, at_8.timestamp_millis());
        let nd2 = Utc.timestamp_millis_opt(next2).single().unwrap();
        assert_eq!((nd2.day(), nd2.hour(), nd2.minute()), (15, 9, 30));
    }

    #[test]
    fn quiet_hours_wrap_midnight() {
        let mut s = Schedule::default();
        s.quiet_start_minute = 22 * 60;
        s.quiet_end_minute = 7 * 60;
        let at_23 = Utc.with_ymd_and_hms(2026, 10, 15, 23, 0, 0).single().unwrap();
        let at_3 = Utc.with_ymd_and_hms(2026, 10, 15, 3, 0, 0).single().unwrap();
        let at_12 = Utc.with_ymd_and_hms(2026, 10, 15, 12, 0, 0).single().unwrap();
        assert!(in_quiet_hours(&s, at_23.timestamp_millis()));
        assert!(in_quiet_hours(&s, at_3.timestamp_millis()));
        assert!(!in_quiet_hours(&s, at_12.timestamp_millis()));
        // disabled when unset
        let off = Schedule::default();
        assert!(!in_quiet_hours(&off, at_23.timestamp_millis()));
    }

    #[test]
    fn roundtrip() {
        let d = std::env::temp_dir().join(format!("rb-tasks-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let mut t = AiTasks::load(&d, "p");
        t.items.push(task(ScheduleKind::Weekly, 5));
        t.items[0].schedule.day_of_week = 3;
        t.save(&d, "p").unwrap();
        let back = AiTasks::load(&d, "p");
        assert_eq!(back.items.len(), 1);
        assert_eq!(back.items[0].schedule.kind, ScheduleKind::Weekly);
        assert_eq!(back.items[0].schedule.day_of_week, 3);
        let _ = std::fs::remove_dir_all(&d);
    }
}
