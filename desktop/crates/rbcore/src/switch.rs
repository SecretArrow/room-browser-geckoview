//! Profile-switch state machine — port of `rb_switch.c` (which is the port
//! of Android's `ProfileSwitchStateMachine`).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Step {
    StopNavigation = 0,
    SaveTabState = 1,
    DestroyBrowserContext = 2,
    FlushProfileState = 3,
    ReleaseProfileResources = 4,
    LoadNewProfileContext = 5,
    RestoreNewProfileTabs = 6,
}

pub const STEP_COUNT: usize = 7;

pub fn step_name(step: Step) -> &'static str {
    match step {
        Step::StopNavigation => "STOP_NAVIGATION",
        Step::SaveTabState => "SAVE_TAB_STATE",
        Step::DestroyBrowserContext => "DESTROY_BROWSER_CONTEXT",
        Step::FlushProfileState => "FLUSH_PROFILE_STATE",
        Step::ReleaseProfileResources => "RELEASE_PROFILE_RESOURCES",
        Step::LoadNewProfileContext => "LOAD_NEW_PROFILE_CONTEXT",
        Step::RestoreNewProfileTabs => "RESTORE_NEW_PROFILE_TABS",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum State {
    #[default]
    Idle,
    Switching,
    Complete,
    Failed,
}

pub fn state_name(s: State) -> &'static str {
    match s {
        State::Idle => "IDLE",
        State::Switching => "SWITCHING",
        State::Complete => "COMPLETE",
        State::Failed => "FAILED",
    }
}

#[derive(Debug, Clone, Default)]
pub struct SwitchMachine {
    pub state: State,
    pub current_step: Option<Step>,
    /// Completion log (order of completion).
    pub completed: Vec<Step>,
    pub from: String,
    pub to: String,
    pub error: String,
}

impl SwitchMachine {
    pub fn new() -> Self {
        SwitchMachine {
            state: State::Idle,
            ..Default::default()
        }
    }

    /// Kotlin's `check(state == IDLE)` and `require(from != to)`.
    pub fn begin(&mut self, from_id: &str, to_id: &str) -> Result<(), String> {
        if self.state != State::Idle {
            return Err("busy: a switch is already in progress".into());
        }
        if from_id.is_empty() || to_id.is_empty() {
            return Err("blank profile id".into());
        }
        if from_id == to_id {
            return Err("a profile may not switch onto itself".into());
        }
        // The completed log is deliberately NOT cleared (same as Kotlin).
        self.from = from_id.to_string();
        self.to = to_id.to_string();
        self.state = State::Switching;
        self.current_step = Some(Step::StopNavigation);
        Ok(())
    }

    pub fn step_done(&mut self) {
        if self.state != State::Switching {
            return;
        }
        if let Some(cur) = self.current_step {
            self.completed.push(cur);
        }
        let last_order = self.completed.last().map(|s| *s as i32).unwrap_or(-1);
        self.current_step = None;
        for i in 0..STEP_COUNT as i32 {
            if i == last_order + 1 {
                self.current_step = Some(match i {
                    0 => Step::StopNavigation,
                    1 => Step::SaveTabState,
                    2 => Step::DestroyBrowserContext,
                    3 => Step::FlushProfileState,
                    4 => Step::ReleaseProfileResources,
                    5 => Step::LoadNewProfileContext,
                    _ => Step::RestoreNewProfileTabs,
                });
                break;
            }
        }
        if self.current_step.is_none() {
            self.state = State::Complete;
        }
    }

    /// Error text is exactly `"step <STEP>: <reason>"`.
    pub fn fail(&mut self, step: Step, reason: &str) {
        if self.state != State::Switching {
            return;
        }
        self.error = format!("step {}: {}", step_name(step), reason);
        self.state = State::Failed;
    }

    /// Abort from any state; the error text survives for reporting.
    pub fn abort(&mut self) {
        self.state = State::Idle;
        self.current_step = None;
    }

    pub fn reset(&mut self) {
        self.state = State::Idle;
        self.current_step = None;
        self.completed.clear();
        self.from.clear();
        self.to.clear();
        self.error.clear();
    }

    pub fn involves(&self, profile_id: &str) -> bool {
        self.state == State::Switching && (self.from == profile_id || self.to == profile_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn walks_all_seven_steps_in_order() {
        let mut m = SwitchMachine::new();
        m.begin("a", "b").unwrap();
        assert_eq!(m.state, State::Switching);
        let mut seen = Vec::new();
        while m.state == State::Switching {
            seen.push(m.current_step.unwrap());
            m.step_done();
        }
        assert_eq!(seen.len(), STEP_COUNT);
        assert_eq!(seen[0], Step::StopNavigation);
        assert_eq!(seen[6], Step::RestoreNewProfileTabs);
        assert_eq!(m.state, State::Complete);
        assert_eq!(m.completed.len(), STEP_COUNT);
    }

    #[test]
    fn busy_same_and_blank_are_refused() {
        let mut m = SwitchMachine::new();
        m.begin("a", "b").unwrap();
        assert!(m.begin("b", "c").is_err());
        let mut n = SwitchMachine::new();
        assert!(n.begin("", "b").is_err());
        assert!(n.begin("a", "a").is_err());
    }

    #[test]
    fn error_text_is_the_kotlin_interpolation() {
        let mut m = SwitchMachine::new();
        m.begin("a", "b").unwrap();
        m.fail(Step::FlushProfileState, "disk full");
        assert_eq!(m.error, "step FLUSH_PROFILE_STATE: disk full");
        assert_eq!(m.state, State::Failed);
        m.abort();
        assert_eq!(m.state, State::Idle);
        assert_eq!(m.error, "step FLUSH_PROFILE_STATE: disk full");
    }
}
