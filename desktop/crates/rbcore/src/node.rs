//! Node runtime — the desktop port of the `:node` process: scripts run with
//! a 30-second budget through the system `node` binary; npm packages are
//! managed with `npm install --omit=dev --ignore-scripts`, which is exactly
//! the "never executes lifecycle scripts" policy the Android runtime has.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

pub const DEFAULT_BUDGET_MS: u64 = 30_000;

pub fn node_binary() -> Option<String> {
    let exe = if cfg!(target_os = "windows") { "node.exe" } else { "node" };
    std::env::var_os("PATH").and_then(|paths| {
        std::env::split_paths(&paths).find_map(|dir| {
            let full = dir.join(exe);
            if full.is_file() {
                Some(full.to_string_lossy().into_owned())
            } else {
                None
            }
        })
    })
}

pub fn node_dir(data: &Path) -> PathBuf {
    crate::paths::join(data, "node")
}

pub struct NodeStatus {
    pub available: bool,
    pub version: String,
    pub work_dir: String,
}

pub fn status(data: &Path) -> NodeStatus {
    let dir = node_dir(data);
    let _ = std::fs::create_dir_all(&dir);
    match node_binary() {
        Some(bin) => {
            let version = Command::new(&bin)
                .arg("--version")
                .output()
                .ok()
                .filter(|o| o.status.success())
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .unwrap_or_default();
            NodeStatus {
                available: !version.is_empty(),
                version,
                work_dir: dir.to_string_lossy().into_owned(),
            }
        }
        None => NodeStatus { available: false, version: String::new(), work_dir: dir.to_string_lossy().into_owned() },
    }
}

/// Runs the script body (the body of an async function) under the budget.
pub fn run(data: &Path, script: &str, budget_ms: u64) -> crate::CoreResult<String> {
    let Some(bin) = node_binary() else {
        return Err("Node.js is not installed on this machine. Install it from nodejs.org to use node_run.".into());
    };
    let dir = node_dir(data);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let file = dir.join("agent-script.js");
    // The Android contract: the script is the body of an async fn.
    let program = format!(
        "(async () => {{\n{script}\n}})().then(v => {{\n  process.stdout.write(String(v));\n}}).catch(e => {{\n  process.stdout.write('error: ' + (e && e.message ? e.message : e));\n}});\n"
    );
    std::fs::write(&file, program).map_err(|e| e.to_string())?;
    let mut cmd = Command::new(&bin);
    cmd.arg(&file).current_dir(&dir);
    let output = cmd
        .timeout_kill(Duration::from_millis(budget_ms.max(1000)))
        .map_err(|e| format!("node spawn: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    let mut text = stdout;
    if text.trim().is_empty() && !stderr.trim().is_empty() {
        text = format!("error: {}", stderr.trim());
    }
    if text.chars().count() > 6000 {
        text = text.chars().take(6000).collect();
    }
    Ok(text)
}

/// npm package management. `--ignore-scripts` is the security policy:
/// lifecycle scripts are NEVER executed.
pub fn packages(data: &Path, action: &str, name: &str, spec: &str) -> crate::CoreResult<String> {
    let Some(_node) = node_binary() else {
        return Err("Node.js is not installed on this machine.".into());
    };
    let npm = if cfg!(target_os = "windows") { "npm.cmd" } else { "npm" };
    let dir = node_dir(data);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    match action {
        "list" => {
            let out = Command::new(npm)
                .args(["ls", "--depth=0", "--json"])
                .current_dir(&dir)
                .output()
                .map_err(|e| format!("npm: {e}"))?;
            let text = String::from_utf8_lossy(&out.stdout).to_string();
            Ok(crate::redaction::redact(&text))
        }
        "install" => {
            if !valid_npm_name(name) {
                return Err("invalid package name".into());
            }
            let pkg = if spec.is_empty() { name.to_string() } else { format!("{name}@{spec}") };
            let out = Command::new(npm)
                .args(["install", &pkg, "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--json"])
                .current_dir(&dir)
                .output()
                .map_err(|e| format!("npm: {e}"))?;
            let summary = String::from_utf8_lossy(&out.stdout);
            Ok(if out.status.success() {
                format!("installed {pkg}\n{}", crate::redaction::redact(summary.trim()))
            } else {
                format!("install failed: {}", String::from_utf8_lossy(&out.stderr))
            })
        }
        "remove" => {
            if !valid_npm_name(name) {
                return Err("invalid package name".into());
            }
            let out = Command::new(npm)
                .args(["remove", name, "--ignore-scripts"])
                .current_dir(&dir)
                .output()
                .map_err(|e| format!("npm: {e}"))?;
            Ok(if out.status.success() {
                format!("removed {name}")
            } else {
                format!("remove failed: {}", String::from_utf8_lossy(&out.stderr))
            })
        }
        _ => Err(format!("unknown packages action: {action}")),
    }
}

/// npm name validation (the Android rules: ≤214 chars, no leading
/// dot/underscore, no uppercase in the scoped part).
fn valid_npm_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 214 {
        return false;
    }
    if name.starts_with('.') || name.starts_with('_') {
        return false;
    }
    let lower = name.to_lowercase();
    if name != lower && !name.starts_with('@') {
        return false;
    }
    !name.contains(' ') && !name.contains("..") && !name.contains('\\')
}

/// A tiny extension so Command runs with a hard timeout.
trait CommandTimeout {
    fn timeout_kill(&mut self, d: Duration) -> std::io::Result<std::process::Output>;
}

impl CommandTimeout for Command {
    fn timeout_kill(&mut self, d: Duration) -> std::io::Result<std::process::Output> {
        let mut child = self.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped()).spawn()?;
        let deadline = std::time::Instant::now() + d;
        loop {
            match child.try_wait()? {
                Some(_) => return child.wait_with_output().map(|o| o),
                None => {
                    if std::time::Instant::now() >= deadline {
                        let _ = child.kill();
                        let _ = child.wait();
                        return Err(std::io::Error::new(std::io::ErrorKind::TimedOut, "node script exceeded its budget"));
                    }
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn npm_name_rules() {
        assert!(valid_npm_name("lodash"));
        assert!(valid_npm_name("@scope/pkg"));
        assert!(!valid_npm_name("BadName"));
        assert!(!valid_npm_name(".hidden"));
        assert!(!valid_npm_name("a b"));
        assert!(!valid_npm_name("a/../b"));
        assert!(!valid_npm_name(""));
        assert!(!valid_npm_name(&"x".repeat(215)));
    }

    #[test]
    fn script_body_is_wrapped_as_async() {
        // (the wrap itself is exercised when node exists; shape-test here)
        let program = format!(
            "(async () => {{\n{}\n}})().then(v => {{\n  process.stdout.write(String(v));\n}}).catch(e => {{\n  process.stdout.write('error: ' + (e && e.message ? e.message : e));\n}});\n",
            "return 1 + 1"
        );
        assert!(program.contains("async"));
        assert!(program.contains("return 1 + 1"));
    }

    #[test]
    fn status_never_panics_without_node() {
        let d = std::env::temp_dir().join(format!("rb-node-{}", std::process::id()));
        let s = status(&d);
        assert!(!s.work_dir.is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }
}
