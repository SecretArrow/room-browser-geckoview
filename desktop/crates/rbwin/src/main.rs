//! Room Browser — Windows frontend (tao window + WebView2 engine).
//!
//! The chrome is an HTML page (rbchrome) in its own webview; every tab is
//! a child webview positioned under it. The chrome talks to this process
//! through WebView2 WebMessages (`RoomHost.call`); the agent runs on a
//! worker thread and drives pages through page-op messages routed back to
//! the UI thread.

#![cfg(target_os = "windows")]

use rbcore::agent::executor::{AppStores, Executor, ToolHost};
use rbcore::agent::loop_runner::LoopHost;
use rbcore::agent::plan::AgentPlan;
use rbcore::agent::{gates::GateContext, AgentConfig, AgentEvent, ChatMessage, ToolCall};
use rbcore::prefs::ProfilePrefs;
use rbcore::profile::{Profile, ProfileRegistry};
use rbcore::{now_ms, CoreResult};
use serde_json::{json, Value};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::mpsc::{channel, Sender};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoop, EventLoopProxy};
use tao::platform::windows::WindowExtWindows;
use tao::window::Window;
use webview2_com::{
    CreateCoreWebView2ControllerCompletedHandler, CreateCoreWebView2EnvironmentCompletedHandler,
    ExecuteScriptCompletedHandler, NavigationCompletedEventHandler, NavigationStartingEventHandler,
    NewWindowRequestedEventHandler, SourceChangedEventHandler, WebMessageReceivedEventHandler,
    WebResourceRequestedEventHandler, DocumentTitleChangedEventHandler,
};
use webview2_com::Microsoft::Web::WebView2::Win32::*;
use windows::core::*;
use windows::Win32::Foundation::{HWND, RECT};

/// A message crossing threads inside the frontend.
pub enum Msg {
    /// RPC from the chrome page.
    Rpc { id: i64, method: String, params: Value },
    /// A page operation requested by the agent worker.
    PageOp { reply: Sender<CoreResult<String>>, op: PageOp },
    /// The agent turn finished.
    AgentDone,
    /// Push an event to the chrome page.
    Chrome { json: Value },
}

pub enum PageOp {
    RunJs { js: String },
    Navigate { url: String },
    Back,
    Forward,
    Reload,
    NewTab { url: String },
    ListTabs,
    SwitchTab { index: usize },
    CloseTab,
    Download { url: String, name: String },
}

struct Tab {
    controller: ICoreWebView2Controller,
    webview: ICoreWebView2,
    url: String,
    title: String,
    loading: bool,
}

struct App {
    hwnd: isize,
    inner: (u32, u32),
    env: ICoreWebView2Environment,
    chrome: ICoreWebView2,
    chrome_controller: ICoreWebView2Controller,
    tabs: Vec<Tab>,
    active: i64,
    data_dir: PathBuf,
    registry: ProfileRegistry,
    prefs: ProfilePrefs,
    profile_id: String,
    filter: rbcore::filters::FilterEngine,
    https: rbcore::https::HttpsPending,
    history: Vec<rbcore::history::HistoryEntry>,
    bookmarks: rbcore::bookmarks::Bookmarks,
    downloads: rbcore::downloads::Downloads,
    vault: rbcore::credentials::CredentialStore,
    vault_passphrase: Option<String>,
    console_buffer: Vec<String>,
    agent_busy: bool,
    agent_sessions: rbcore::agent::sessions::AgentSessions,
}

type AppRef = Rc<RefCell<Option<App>>>;

fn hwnd_of(window: &Window) -> HWND {
    HWND(window.hwnd() as *mut core::ffi::c_void)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Reads a PWSTR out-parameter into a String (null-safe).
unsafe fn take_pwstr(f: impl FnOnce(*mut PWSTR) -> windows::core::Result<()>) -> String {
    let mut raw = PWSTR::null();
    if f(&mut raw).is_ok() {
        if raw.is_null() {
            String::new()
        } else {
            raw.to_string().unwrap_or_default()
        }
    } else {
        String::new()
    }
}

impl App {
    fn push_chrome(&self, name: &str, payload: Value) {
        let _ = unsafe {
            self.chrome.PostWebMessageAsJson(&HSTRING::from(&json!({
                "__event": name, "payload": payload
            }).to_string()))
        };
    }

    /// Replies to an RPC: one message carrying {__reply, ok, value}.
    fn reply(&self, id: i64, ok: bool, value: Value) {
        let _ = unsafe {
            self.chrome.PostWebMessageAsJson(&HSTRING::from(&json!({
                "__reply": id, "ok": ok, "value": value
            }).to_string()))
        };
    }

    fn sync_tabs(&self) {
        let tabs: Vec<Value> = self
            .tabs
            .iter()
            .map(|t| json!({"title": t.title, "url": t.url}))
            .collect();
        self.push_chrome("tabs", json!({"tabs": tabs, "active": self.active}));
    }

    fn sync_page(&self) {
        let t = self.tabs.get(self.active as usize);
        self.push_chrome(
            "page",
            json!({
                "url": t.map(|t| t.url.clone()).unwrap_or_default(),
                "display": t.map(|t| rbcore::urlintel::display_url(&t.url)).unwrap_or_default(),
                "title": t.map(|t| t.title.clone()).unwrap_or_default(),
                "loading": t.map(|t| t.loading).unwrap_or(false),
            }),
        );
    }

    fn active_webview(&self) -> Option<&ICoreWebView2> {
        self.tabs.get(self.active as usize).map(|t| &t.webview)
    }

    fn layout(&self) {
        let (w, h) = (self.inner.0 as i32, self.inner.1 as i32);
        let chrome_h = 132i32; // tab strip + toolbar + bookmarks bar
        unsafe {
            let _ = self.chrome_controller.SetBounds(RECT { left: 0, top: 0, right: w, bottom: chrome_h });
            for (i, t) in self.tabs.iter().enumerate() {
                let rect = RECT { left: 0, top: chrome_h, right: w, bottom: h };
                let _ = t.controller.SetBounds(rect);
                let _ = t.controller.SetIsVisible(i == self.active as usize);
            }
        }
    }

    fn record_history(&mut self, url: &str, title: &str) {
        if url.starts_with("about:") || url.is_empty() {
            return;
        }
        rbcore::history::record(&mut self.history, url, title, now_ms());
        let _ = rbcore::history::save(&rbcore::paths::history_path(&self.data_dir), &self.history);
    }

    fn vault_path(&self) -> PathBuf {
        rbcore::paths::join(&rbcore::paths::profile_settings(&self.data_dir, &self.profile_id), "credentials.jsonl")
    }
}

fn main() {
    // CLI surface: --version prints one line and exits, before any engine
    // or window is touched (the smoke test relies on this).
    if std::env::args().any(|a| a == "--version") {
        rbcore::println_out(&format!("Room Browser {} ({})", rbcore::version(), "windows x86_64"));
        return;
    }
    let data_dir = rbcore::paths::data_dir();
    std::fs::create_dir_all(&data_dir).ok();

    let mut registry = ProfileRegistry::load(&data_dir);
    registry.repair_theme_setting();
    registry.repair_default();
    let _ = registry.save(&data_dir);
    let profile_id = registry
        .active
        .clone()
        .or_else(|| registry.profiles.first().map(|p| p.id.clone()))
        .unwrap_or_default();
    rbcore::paths::ensure_profile_tree(&data_dir, &profile_id).ok();
    let profile = registry.get(&profile_id).cloned().unwrap_or(Profile::default());
    let prefs = profile.settings.clone();

    // WebRTC policy rides WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS, set BEFORE
    // environment creation (the same launch-time mechanism the C edition used).
    if prefs.webrtc_policy() != "default" {
        let extra = "--force-webrtc-ip-handling-policy=disable_non_proxied_udp";
        let existing = std::env::var("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS").unwrap_or_default();
        if !existing.contains("webrtc-ip-handling") {
            std::env::set_var("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", format!("{existing} {extra}"));
        }
    }

    let event_loop: EventLoop<Msg> = tao::event_loop::EventLoopBuilder::<Msg>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let window = Window::new(&event_loop).expect("window");
    window.set_title("Room Browser");

    let browser_data = rbcore::paths::profile_browser_data(&data_dir, &profile_id);
    let env = unsafe { create_environment(&browser_data).expect("WebView2 environment") };

    let app: AppRef = Rc::new(RefCell::new(None));

    let chrome_url = rbchrome::chrome_url(&data_dir, rbcore::version());
    let chrome_controller = unsafe { create_controller_blocking(&env, hwnd_of(&window), Some(&chrome_url)).expect("chrome controller") };
    let chrome = unsafe { chrome_controller.CoreWebView2().unwrap() };
    install_chrome_handlers(&chrome, proxy.clone());

    let mut filter = rbcore::filters::FilterEngine::new();
    filter.load_builtin();

    {
        let mut downloads = rbcore::downloads::Downloads::load(&rbcore::paths::downloads_path(&data_dir));
        let n = downloads.reconcile(&profile_id, "Interrupted", now_ms());
        if n > 0 {
            let _ = downloads.save(&rbcore::paths::downloads_path(&data_dir));
        }
        let size0 = window.inner_size();
        let inner = App {
            hwnd: window.hwnd(),
            inner: (size0.width, size0.height),
            env: env.clone(),
            chrome: chrome.clone(),
            chrome_controller,
            tabs: Vec::new(),
            active: -1,
            data_dir: data_dir.clone(),
            registry,
            prefs: prefs.clone(),
            profile_id: profile_id.clone(),
            filter,
            https: rbcore::https::HttpsPending::new(),
            history: rbcore::history::load(&rbcore::paths::history_path(&data_dir)),
            bookmarks: rbcore::bookmarks::Bookmarks::load(&rbcore::paths::bookmarks_path(&data_dir)),
            downloads,
            vault: rbcore::credentials::CredentialStore::load(
                &rbcore::paths::join(&rbcore::paths::profile_settings(&data_dir, &profile_id), "credentials.jsonl"),
            ),
            vault_passphrase: None,
            console_buffer: Vec::new(),
            agent_busy: false,
            agent_sessions: rbcore::agent::sessions::AgentSessions::load(&data_dir, &profile_id),
        };
        *app.borrow_mut() = Some(inner);
    }

    // Session restore (open_tabs/active_tab from the profile settings).
    {
        let mut a = app.borrow_mut();
        let a = a.as_mut().unwrap();
        let open = a.prefs.open_tabs();
        let active = a.prefs.active_tab();
        let env2 = a.env.clone();
        let hwnd = HWND(a.hwnd as *mut core::ffi::c_void);
        let prefs2 = a.prefs.clone();
        drop(a);
        if open.is_empty() {
            let home = if prefs2.homepage_enabled() { prefs2.homepage() } else { String::new() };
            spawn_tab(&env2, hwnd, app.clone(), &home, &prefs2);
        } else {
            for url in &open {
                spawn_tab(&env2, hwnd, app.clone(), url, &prefs2);
            }
            let _ = select_tab(app.clone(), active);
        }
        let a = app.borrow();
        let a = a.as_ref().unwrap();
        a.layout();
        a.sync_tabs();
        a.sync_page();
    }

    event_loop.run(move |event, _, control_flow| {
        let _ = &window; // keep the window alive for the loop's lifetime
        *control_flow = ControlFlow::Wait;
        match event {
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                let mut a = app.borrow_mut();
                if let Some(a) = a.as_mut() {
                    let urls: Vec<String> = a.tabs.iter().map(|t| t.url.clone()).collect();
                    if let Some(p) = a.registry.get_mut(&a.profile_id) {
                        p.settings.set_session(&urls, a.active.max(0) as usize);
                    }
                    let _ = a.registry.save(&a.data_dir);
                    let _ = a.agent_sessions.save(&a.data_dir, &a.profile_id);
                }
                *control_flow = ControlFlow::Exit;
            }
            Event::WindowEvent { event: WindowEvent::Resized(size), .. } => {
                let mut a = app.borrow_mut();
                if let Some(a) = a.as_mut() {
                    a.inner = (size.width, size.height);
                    a.layout();
                }
            }
            Event::UserEvent(msg) => handle_msg(app.clone(), proxy.clone(), msg),
            _ => {}
        }
    });
}

// ------------------------------------------------------------------
// WebView2 plumbing

/// Creates the environment (blocking with a message pump; call before the
/// event loop starts).
unsafe fn create_environment(user_data: &PathBuf) -> CoreResult<ICoreWebView2Environment> {
    let user_data_w = wide(&user_data.to_string_lossy());
    let (tx, rx) = channel::<ICoreWebView2Environment>();
    CreateCoreWebView2EnvironmentCompletedHandler::wait_for_async_operation(
        Box::new(move |handler| {
            CreateCoreWebView2EnvironmentWithOptions(
                PCWSTR::null(),
                PCWSTR::from_raw(user_data_w.as_ptr()),
                None,
                &handler,
            )
            .map_err(webview2_com::Error::WindowsError)
        }),
        Box::new(move |err, environment| {
            if let Err(e) = &err {
                let _ = e; // surfaced through wait_for_async_operation
            }
            if let Some(env) = environment {
                let _ = tx.send(env);
            }
            Ok(())
        }),
    )
    .map_err(|e| format!("environment: {e}"))?;
    rx.recv().map_err(|e| format!("environment wait: {e}"))
}

/// Creates a controller (blocking, message-pumped; pre-loop only).
unsafe fn create_controller_blocking(env: &ICoreWebView2Environment, hwnd: HWND, url: Option<&str>) -> CoreResult<ICoreWebView2Controller> {
    let (tx, rx) = channel::<ICoreWebView2Controller>();
    let env = env.clone();
    CreateCoreWebView2ControllerCompletedHandler::wait_for_async_operation(
        Box::new(move |handler| {
            env.CreateCoreWebView2Controller(hwnd, &handler)
                .map_err(webview2_com::Error::WindowsError)
        }),
        Box::new(move |err, controller| {
            let _ = &err;
            if let Some(c) = controller {
                let _ = tx.send(c);
            }
            Ok(())
        }),
    )
    .map_err(|e| format!("controller: {e}"))?;
    let controller = rx.recv().map_err(|e| format!("controller wait: {e}"))?;
    if let Some(u) = url {
        if let Ok(wv) = controller.CoreWebView2() {
            let w = wide(u);
            let _ = wv.Navigate(PCWSTR::from_raw(w.as_ptr()));
        }
    }
    Ok(controller)
}

/// Creates a controller asynchronously; `on_ready` runs when the controller
/// exists (dispatched by the message loop).
unsafe fn create_controller_async(
    env: &ICoreWebView2Environment,
    hwnd: HWND,
    on_ready: Box<dyn FnOnce(CoreResult<ICoreWebView2Controller>)>,
) {
    let handler = CreateCoreWebView2ControllerCompletedHandler::create(Box::new(
        move |err: windows::core::Result<()>, controller: Option<ICoreWebView2Controller>| {
            if let Err(e) = err {
                on_ready(Err(format!("controller: {e}")));
            } else if let Some(c) = controller {
                on_ready(Ok(c));
            } else {
                on_ready(Err("no controller".into()));
            }
            Ok(())
        },
    ));
    let _ = env.CreateCoreWebView2Controller(hwnd, &handler);
}

/// Runs a script and hands the JSON-encoded result to `done` — never blocks
/// the loop.
fn execute_script_then(webview: &ICoreWebView2, js: &str, done: Box<dyn FnOnce(CoreResult<String>)>) {
    // The completion may never fire if ExecuteScript itself fails, so the
    // callback is shared and taken exactly once.
    let done = Rc::new(RefCell::new(Some(done)));
    let done2 = done.clone();
    let handler = ExecuteScriptCompletedHandler::create(Box::new(
        move |err: windows::core::Result<()>, result: String| {
            if let Some(f) = done2.borrow_mut().take() {
                if let Err(e) = err {
                    f(Err(format!("script: {e}")));
                } else {
                    f(Ok(result));
                }
            }
            Ok(())
        },
    ));
    if let Err(e) = unsafe { webview.ExecuteScript(&HSTRING::from(js), &handler) } {
        if let Some(f) = done.borrow_mut().take() {
            f(Err(format!("script: {e}")));
        }
    }
}



fn init_scripts(prefs: &ProfilePrefs) -> Vec<String> {
    let mut scripts = vec![
        rbcore::bridge::BRIDGE_BOOT.to_string(),
        rbcore::bridge::ROOM_CONSOLE_SCRIPT.to_string(),
        rbcore::bridge::ROOM_VAULT_SCRIPT.to_string(),
        rbcore::bridge::ROOM_WALLET_SCRIPT.to_string(),
    ];
    let device = if prefs.device_id().is_empty() {
        None
    } else {
        rbcore::devices::by_id(&prefs.device_id()).cloned()
    };
    let screen = if prefs.screen_size_manual() && prefs.screen_width() > 0 && prefs.screen_height() > 0 {
        Some((prefs.screen_width() as i32, prefs.screen_height() as i32))
    } else {
        None
    };
    if let Some(js) = rbcore::shims::combined_shim_js(device.as_ref(), screen) {
        scripts.push(js);
    }
    scripts
}

unsafe fn add_init_script(webview: &ICoreWebView2, script: &str) {
    use webview2_com::AddScriptToExecuteOnDocumentCreatedCompletedHandler;
    let handler = AddScriptToExecuteOnDocumentCreatedCompletedHandler::create(Box::new(
        |_err: windows::core::Result<()>, _id: String| Ok(()),
    ));
    let _ = webview.AddScriptToExecuteOnDocumentCreated(&HSTRING::from(script), &handler);
}

fn spawn_tab(env: &ICoreWebView2Environment, hwnd: HWND, app_ref: AppRef, url: &str, prefs: &ProfilePrefs) {
    let controller = unsafe { create_controller_blocking(env, hwnd, if url.is_empty() { None } else { Some(url) }) }.expect("tab controller");
    let webview = unsafe { controller.CoreWebView2().unwrap() };
    finish_tab_setup(&webview, prefs);
    install_tab_handlers(&webview, app_ref.clone());
    let mut a = app_ref.borrow_mut();
    let a = a.as_mut().unwrap();
    a.tabs.push(Tab { controller, webview, url: url.to_string(), title: String::new(), loading: false });
    a.active = (a.tabs.len() - 1) as i64;
}

/// Mid-loop tab creation: the controller arrives asynchronously and the
/// continuation finishes the setup (no nested message pump, no deadlock).
fn spawn_tab_async(
    env: &ICoreWebView2Environment,
    hwnd: HWND,
    app_ref: AppRef,
    url: &str,
    prefs: &ProfilePrefs,
    on_ready: Box<dyn FnOnce(&AppRef)>,
) {
    let url = url.to_string();
    let prefs = prefs.clone();
    let app_for_cb = app_ref.clone();
    unsafe {
        create_controller_async(
            env,
            hwnd,
            Box::new(move |r| {
                match r {
                    Ok(controller) => {
                        if let Ok(webview) = controller.CoreWebView2() {
                            finish_tab_setup(&webview, &prefs);
                            let app_ref = app_for_cb.clone();
                            install_tab_handlers(&webview, app_ref.clone());
                            let mut a = app_ref.borrow_mut();
                            if let Some(a) = a.as_mut() {
                                a.tabs.push(Tab {
                                    controller,
                                    webview,
                                    url: url.clone(),
                                    title: String::new(),
                                    loading: false,
                                });
                                a.active = (a.tabs.len() - 1) as i64;
                            }
                            drop(a);
                            on_ready(&app_ref);
                        }
                    }
                    Err(_) => {}
                }
            }),
        );
    }
}

fn finish_tab_setup(webview: &ICoreWebView2, prefs: &ProfilePrefs) {
    unsafe {
        if let Ok(settings) = webview.Settings() {
            let _ = settings.SetIsScriptEnabled(prefs.javascript());
            let _ = settings.SetIsStatusBarEnabled(false);
            if let (Some(ua), Ok(s2)) = (prefs.effective_user_agent(), settings.cast::<ICoreWebView2Settings2>()) {
                let w = wide(&ua);
                let _ = s2.SetUserAgent(PCWSTR::from_raw(w.as_ptr()));
            }
        }
    }
    for js in init_scripts(prefs) {
        unsafe { add_init_script(webview, &js) };
    }
    unsafe {
        let _ = webview.AddWebResourceRequestedFilter(&HSTRING::from("*"), COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    }
}

fn select_tab(app_ref: AppRef, index: usize) -> CoreResult<()> {
    let mut a = app_ref.borrow_mut();
    let a = a.as_mut().ok_or("app gone")?;
    if index >= a.tabs.len() {
        return Err("no tab at that index".into());
    }
    a.active = index as i64;
    a.layout();
    a.sync_tabs();
    a.sync_page();
    Ok(())
}

fn install_chrome_handlers(chrome: &ICoreWebView2, proxy: EventLoopProxy<Msg>) {
    let handler = WebMessageReceivedEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, args: Option<ICoreWebView2WebMessageReceivedEventArgs>| {
            if let Some(args) = args {
                let msg = unsafe { take_pwstr(|p| args.TryGetWebMessageAsString(p)) };
                if !msg.is_empty() {
                    if let Ok(v) = serde_json::from_str::<Value>(&msg) {
                        if let (Some(id), Some(method)) = (v.get("id").and_then(|x| x.as_i64()), v.get("method").and_then(|x| x.as_str())) {
                            let params = v.get("params").cloned().unwrap_or(Value::Null);
                            let _ = proxy.send_event(Msg::Rpc { id, method: method.to_string(), params });
                        }
                    }
                }
            }
            Ok(())
        },
    ));
    unsafe {
        let mut token: i64 = 0;
        chrome.add_WebMessageReceived(&handler, &mut token).ok();
    }
}

fn install_tab_handlers(webview: &ICoreWebView2, app_ref: AppRef) {
    // Content blocking on every request (the same decision engine as the
    // Android WebView edition's shouldInterceptRequest path).
    unsafe {
        let _ = webview.AddWebResourceRequestedFilter(&HSTRING::from("*"), COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    }

    let app_ref0 = app_ref.clone();
    let nav_start = NavigationStartingEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, args: Option<ICoreWebView2NavigationStartingEventArgs>| {
            if let Some(args) = args {
                let uri = unsafe { take_pwstr(|p| args.Uri(p)) };
                if !uri.is_empty() {
                    let mut a = app_ref0.borrow_mut();
                    let a = match a.as_mut() { Some(a) => a, None => return Ok(()) };
                    let host = rbcore::urlintel::host_of(&uri).unwrap_or_default();
                    let opts = rbcore::filters::Options::from_prefs(&a.prefs);
                    if opts.block_malicious && a.filter.blocked_category(&host) == rbcore::filters::Category::Malicious {
                        let _ = unsafe { args.SetCancel(true) };
                    } else if a.prefs.https_upgrade() {
                        if let Some(upgraded) = rbcore::https::upgrade_to_https(&uri) {
                            let _ = unsafe { args.SetCancel(true) };
                            let w = wide(&upgraded);
                            if let Some(wv) = a.active_webview() {
                                let _ = unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) };
                            }
                            a.https.register(&upgraded, &uri);
                        }
                    }
                }
            }
            Ok(())
        },
    ));

    let app_ref1 = app_ref.clone();
    let nav_done = NavigationCompletedEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, _args: Option<ICoreWebView2NavigationCompletedEventArgs>| {
            let mut a = app_ref1.borrow_mut();
            if let Some(a) = a.as_mut() {
                if let Some(t) = a.tabs.get_mut(a.active as usize) {
                    t.loading = false;
                }
                a.sync_page();
            }
            Ok(())
        },
    ));

    let app_ref2 = app_ref.clone();
    let title_changed = DocumentTitleChangedEventHandler::create(Box::new(
        move |sender: Option<ICoreWebView2>, _args: Option<windows::core::IUnknown>| {
            if let Some(wv) = sender {
                let title = unsafe { take_pwstr(|p| wv.DocumentTitle(p)) };
                let mut a = app_ref2.borrow_mut();
                if let Some(a) = a.as_mut() {
                    if let Some(t) = a.tabs.get_mut(a.active as usize) {
                        t.title = title.clone();
                    }
                    let url = a.tabs.get(a.active as usize).map(|t| t.url.clone()).unwrap_or_default();
                    if !url.is_empty() {
                        a.record_history(&url, &title);
                    }
                    a.sync_tabs();
                    a.sync_page();
                }
            }
            Ok(())
        },
    ));

    let app_ref3 = app_ref.clone();
    let src_changed = SourceChangedEventHandler::create(Box::new(
        move |sender: Option<ICoreWebView2>, _args: Option<ICoreWebView2SourceChangedEventArgs>| {
            if let Some(wv) = sender {
                let uri = unsafe { take_pwstr(|p| wv.Source(p)) };
                if !uri.is_empty() {
                    let mut a = app_ref3.borrow_mut();
                    if let Some(a) = a.as_mut() {
                        if let Some(t) = a.tabs.get_mut(a.active as usize) {
                            t.url = uri.clone();
                            t.loading = true;
                        }
                        a.sync_page();
                    }
                }
            }
            Ok(())
        },
    ));

    let app_ref4 = app_ref.clone();
    let new_window = NewWindowRequestedEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, args: Option<ICoreWebView2NewWindowRequestedEventArgs>| {
            if let Some(args) = args {
                let uri = unsafe { take_pwstr(|p| args.Uri(p)) };
                let _ = unsafe { args.SetHandled(true) };
                let (blocked, env, hwnd, prefs) = {
                    let a = app_ref4.borrow();
                    match a.as_ref() {
                        Some(a) => (a.prefs.block_popups(), a.env.clone(), HWND(a.hwnd as *mut core::ffi::c_void), a.prefs.clone()),
                        None => return Ok(()),
                    }
                };
                if !blocked {
                    let app_ref5 = app_ref4.clone();
                    spawn_tab_async(&env, hwnd, app_ref5, &uri, &prefs, Box::new(|app2| {
                        let a = app2.borrow();
                        if let Some(a) = a.as_ref() {
                            a.layout();
                            a.sync_tabs();
                        }
                    }));
                }
            }
            Ok(())
        },
    ));

    let app_ref6 = app_ref.clone();
    let res_requested = WebResourceRequestedEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, args: Option<ICoreWebView2WebResourceRequestedEventArgs>| {
            use windows::Win32::System::Com::IStream;
            if let Some(args) = args {
                if let Ok(request) = unsafe { args.Request() } {
                    let uri = unsafe { take_pwstr(|p| request.Uri(p)) };
                    if !uri.is_empty() {
                        let (blocked, env) = {
                            let a = app_ref6.borrow();
                            match a.as_ref() {
                                Some(a) => {
                                    let host = rbcore::urlintel::host_of(&uri).unwrap_or_default();
                                    let path = match uri.find(&host) {
                                        Some(i) => uri[i + host.len()..].to_string(),
                                        None => String::new(),
                                    };
                                    let page_host = a
                                        .tabs
                                        .get(a.active as usize)
                                        .and_then(|t| rbcore::urlintel::host_of(&t.url))
                                        .unwrap_or_default();
                                    let opts = rbcore::filters::Options::from_prefs(&a.prefs);
                                    let decision = a.filter.decide(&host, &page_host, &path, &opts);
                                    (decision.0 != rbcore::filters::Category::None, a.env.clone())
                                }
                                None => return Ok(()),
                            }
                        };
                        if blocked {
                            let content: Option<&IStream> = None;
                            if let Ok(resp) = unsafe {
                                env.CreateWebResourceResponse(
                                    content,
                                    200,
                                    PCWSTR::from_raw(wide("OK").as_ptr()),
                                    PCWSTR::from_raw(wide("").as_ptr()),
                                )
                            } {
                                let _ = unsafe { args.SetResponse(&resp) };
                            }
                        }
                    }
                }
            }
            Ok(())
        },
    ));

    // The vault/wallet page-side bridges message the host through tab
    // webviews too.
    let app_ref7 = app_ref.clone();
    let tab_msg = WebMessageReceivedEventHandler::create(Box::new(
        move |_sender: Option<ICoreWebView2>, args: Option<ICoreWebView2WebMessageReceivedEventArgs>| {
            if let Some(args) = args {
                let msg = unsafe { take_pwstr(|p| args.TryGetWebMessageAsString(p)) };
                if !msg.is_empty() {
                    if let Ok(v) = serde_json::from_str::<Value>(&msg) {
                        let kind = v.get("kind").and_then(|k| k.as_str()).unwrap_or("").to_string();
                        let mut a = app_ref7.borrow_mut();
                        let a = match a.as_mut() { Some(a) => a, None => return Ok(()) };
                        match kind.as_str() {
                            "vault.report" => {
                                let host = v.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                let username = v.get("username").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                let password = v.get("password").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                a.push_chrome("vault.offer", json!({"host": host, "username": username, "password": password}));
                            }
                            "vault.request" => {
                                let host = v.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                let entries: Vec<Value> = a
                                    .vault
                                    .for_host(&host)
                                    .iter()
                                    .map(|c| json!({"username": c.username, "domain": c.domain}))
                                    .collect();
                                a.push_chrome("vault.suggest", json!({"host": host, "entries": entries}));
                            }
                            "console" => {
                                let text = v.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                a.console_buffer.push(text);
                                if a.console_buffer.len() > 500 {
                                    a.console_buffer.remove(0);
                                }
                            }
                            _ => {}
                        }
                    }
                }
            }
            Ok(())
        },
    ));

    unsafe {
        let mut token: i64 = 0;
        let _ = webview.add_NavigationStarting(&nav_start, &mut token);
        let _ = webview.add_NavigationCompleted(&nav_done, &mut token);
        let _ = webview.add_DocumentTitleChanged(&title_changed, &mut token);
        let _ = webview.add_SourceChanged(&src_changed, &mut token);
        let _ = webview.add_NewWindowRequested(&new_window, &mut token);
        let _ = webview.add_WebResourceRequested(&res_requested, &mut token);
        let _ = webview.add_WebMessageReceived(&tab_msg, &mut token);
    }
}

// ------------------------------------------------------------------
// Message handling

fn handle_msg(app: AppRef, proxy: EventLoopProxy<Msg>, msg: Msg) {
    match msg {
        Msg::Rpc { id, method, params } => {
            let result = dispatch_rpc(&app, &proxy, id, &method, &params);
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                match result {
                    Ok(v) => a.reply(id, true, v),
                    Err(e) => a.reply(id, false, Value::String(e)),
                }
            }
        }
        Msg::PageOp { reply, op } => {
            if let PageOp::RunJs { js } = &op {
                // Script results arrive asynchronously; the reply rides the
                // completion so the loop is never blocked.
                let a = app.borrow();
                match a.as_ref().and_then(|a| a.active_webview().cloned()) {
                    Some(wv) => {
                        drop(a);
                        let js = js.clone();
                        execute_script_then(&wv, &js, Box::new(move |r| {
                            let _ = reply.send(r);
                        }));
                    }
                    _ => {
                        let _ = reply.send(Err("no active tab".into()));
                    }
                }
                return;
            }
            let a = app.borrow();
            match a.as_ref() {
                Some(a) => {
                    let r = run_page_op(a, &app, &op);
                    let _ = reply.send(r);
                }
                None => {
                    let _ = reply.send(Err("app gone".into()));
                }
            }
        }
        Msg::AgentDone => {
            let mut a = app.borrow_mut();
            if let Some(a) = a.as_mut() {
                a.agent_busy = false;
                let _ = a.agent_sessions.save(&a.data_dir, &a.profile_id);
            }
        }
        Msg::Chrome { json } => {
            let a = app.borrow();
            if let Some(a) = a.as_ref() {
                let name = json.get("name").and_then(|x| x.as_str()).unwrap_or("");
                let payload = json.get("payload").cloned().unwrap_or(Value::Null);
                a.push_chrome(name, payload);
            }
        }
    }
}

fn run_page_op(a: &App, app_ref: &AppRef, op: &PageOp) -> CoreResult<String> {
    match op {
        // RunJs is intercepted in handle_msg (its result is asynchronous).
        PageOp::RunJs { .. } => Ok(String::new()),
        PageOp::Navigate { url } => {
            let wv = a.active_webview().ok_or("no active tab")?;
            let w = wide(url);
            unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) }.map_err(|e| e.to_string())?;
            Ok("navigating".into())
        }
        PageOp::Back => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.GoBack() };
            }
            Ok("back".into())
        }
        PageOp::Forward => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.GoForward() };
            }
            Ok("forward".into())
        }
        PageOp::Reload => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.Reload() };
            }
            Ok("reloaded".into())
        }
        PageOp::NewTab { url } => {
            let env = a.env.clone();
            let hwnd = HWND(a.hwnd as *mut core::ffi::c_void);
            let prefs = a.prefs.clone();
            spawn_tab_async(&env, hwnd, app_ref.clone(), url, &prefs, Box::new(|_| {}));
            Ok("new tab".into())
        }
        PageOp::ListTabs => Ok(serde_json::to_string(
            &a.tabs.iter().map(|t| (t.title.clone(), t.url.clone())).collect::<Vec<_>>(),
        ).map_err(|e| e.to_string())?),
        PageOp::SwitchTab { index } => {
            drop(a);
            select_tab(app_ref.clone(), *index).map(|_| "switched".into())
        }
        PageOp::CloseTab => {
            let mut a2 = app_ref.borrow_mut();
            if let Some(a2) = a2.as_mut() {
                if !a2.tabs.is_empty() {
                    a2.tabs.remove(a2.active as usize);
                    a2.active = (a2.tabs.len() as i64 - 1).max(0);
                    a2.layout();
                    a2.sync_tabs();
                    a2.sync_page();
                }
            }
            Ok("closed".into())
        }
        PageOp::Download { url, name } => {
            Ok(format!("download record queued: {url} {name}"))
        }
    }
}

fn dispatch_rpc(app: &AppRef, proxy: &EventLoopProxy<Msg>, id: i64, method: &str, params: &Value) -> CoreResult<Value> {
    let mut a = app.borrow_mut();
    let a = a.as_mut().ok_or("app not ready")?;
    match method {
        "init" => Ok(json!({
            "tabs": a.tabs.iter().map(|t| json!({"title": t.title, "url": t.url})).collect::<Vec<_>>(),
            "active": a.active,
            "model": "Ollama",
            "theme": theme_json(a),
        })),
        "omnibox.submit" => {
            let input = params.get("input").and_then(|x| x.as_str()).unwrap_or("");
            let url = rbcore::urlintel::decide(input, &a.prefs.search_engine());
            let w = wide(&url);
            if let Some(wv) = a.active_webview() {
                unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) }.map_err(|e| e.to_string())?;
            }
            Ok(json!({"url": url}))
        }
        "tab.new" => {
            let env = a.env.clone();
            let hwnd = HWND(a.hwnd as *mut core::ffi::c_void);
            let prefs = a.prefs.clone();
            let home = if prefs.homepage_enabled() { prefs.homepage() } else { String::new() };
            drop(a);
            spawn_tab_async(&env, hwnd, app.clone(), &home, &prefs, Box::new(|app2| {
                let a = app2.borrow();
                if let Some(a) = a.as_ref() {
                    a.layout();
                    a.sync_tabs();
                }
            }));
            Ok(Value::Null)
        }
        "tab.close" => {
            let index = params.get("index").and_then(|x| x.as_u64()).unwrap_or(a.active.max(0) as u64) as usize;
            if index < a.tabs.len() {
                a.tabs.remove(index);
                a.active = (a.tabs.len() as i64 - 1).max(0);
            }
            a.layout();
            a.sync_tabs();
            a.sync_page();
            Ok(Value::Null)
        }
        "tab.select" => {
            let index = params.get("index").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
            a.active = index.min(a.tabs.len().saturating_sub(1)) as i64;
            a.layout();
            a.sync_tabs();
            a.sync_page();
            Ok(Value::Null)
        }
        "tab.navigate" => {
            let url = params.get("url").and_then(|x| x.as_str()).unwrap_or("");
            let url = rbcore::urlintel::decide(url, &a.prefs.search_engine());
            let w = wide(&url);
            if let Some(wv) = a.active_webview() {
                unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) }.map_err(|e| e.to_string())?;
            }
            Ok(Value::Null)
        }
        "page.back" => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.GoBack() };
            }
            Ok(Value::Null)
        }
        "page.forward" => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.GoForward() };
            }
            Ok(Value::Null)
        }
        "page.reload" => {
            if let Some(wv) = a.active_webview() {
                let _ = unsafe { wv.Reload() };
            }
            Ok(Value::Null)
        }
        "page.stop" => {
            Ok(Value::Null)
        }
        "page.home" => {
            let home = a.prefs.homepage();
            let w = wide(&home);
            if let Some(wv) = a.active_webview() {
                unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) }.map_err(|e| e.to_string())?;
            }
            Ok(Value::Null)
        }
        "page.translate" => {
            let url = a.tabs.get(a.active as usize).map(|t| t.url.clone()).unwrap_or_default();
            if url.starts_with("about:") || url.is_empty() {
                return Ok(Value::Null);
            }
            let target = a.prefs.translate_target_language();
            let translated = format!("https://translate.google.com/translate?sl=auto&tl={target}&u={url}");
            let w = wide(&translated);
            if let Some(wv) = a.active_webview() {
                unsafe { wv.Navigate(PCWSTR::from_raw(w.as_ptr())) }.map_err(|e| e.to_string())?;
            }
            Ok(Value::Null)
        }
        "page.find" => {
            let query = params.get("query").and_then(|x| x.as_str()).unwrap_or("").to_string();
            drop(a);
            let app2 = app.clone();
            let wv = {
                let a = app2.borrow();
                let a = a.as_ref().ok_or("no tab")?;
                a.active_webview().cloned().ok_or("no tab")?
            };
            execute_script_then(&wv, &rbcore::bridge::find_count_js(&query), Box::new(move |r| {
                let n: i64 = r.map(|c| c.trim_matches('"').parse().unwrap_or(0)).unwrap_or(0);
                let a = app2.borrow();
                if let Some(a) = a.as_ref() {
                    a.reply(id, true, json!({"count": n}));
                }
            }));
            return Ok(Value::Null);
        }
        "bookmark.toggle" => {
            let url = a.tabs.get(a.active as usize).map(|t| t.url.clone()).unwrap_or_default();
            let title = a.tabs.get(a.active as usize).map(|t| t.title.clone()).unwrap_or_default();
            if a.bookmarks.has(&url) {
                a.bookmarks.remove_by_url(&url);
            } else {
                a.bookmarks.add(&url, &title, None, now_ms());
            }
            a.bookmarks.save(&rbcore::paths::bookmarks_path(&a.data_dir)).map_err(|e| e.to_string())?;
            Ok(json!({"bookmarked": a.bookmarks.has(&url)}))
        }
        "bookmark.remove" => {
            let url = params.get("url").and_then(|x| x.as_str()).unwrap_or("").to_string();
            a.bookmarks.remove_by_url(&url);
            a.bookmarks.save(&rbcore::paths::bookmarks_path(&a.data_dir)).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "bookmarks.list" => Ok(json!(a
            .bookmarks
            .display_order()
            .iter()
            .map(|b| json!({"title": b.title, "url": b.url}))
            .collect::<Vec<_>>())),
        "history.open" => {
            let rows: Vec<Value> = a.history.iter().take(20).map(|h| json!({"title": h.title, "url": h.url})).collect();
            a.push_chrome("history", json!({"rows": rows}));
            Ok(Value::Null)
        }
        "pref.toggle" => {
            let key = params.get("key").and_then(|x| x.as_str()).unwrap_or("").to_string();
            {
                let new_prefs = {
                    let p = a.registry.get_mut(&a.profile_id);
                    match p {
                        Some(p) => {
                            let cur = p.settings.bool_of(&key, true);
                            p.settings.set_bool(&key, !cur);
                            Some(p.settings.clone())
                        }
                        None => None,
                    }
                };
                if let Some(np) = new_prefs {
                    let _ = a.registry.save(&a.data_dir);
                    a.prefs = np;
                }
            }
            Ok(Value::Null)
        }
        "pref.set" => {
            let key = params.get("key").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let value = params.get("value").and_then(|x| x.as_str()).unwrap_or("").to_string();
            {
                let new_prefs = a
                    .registry
                    .get_mut(&a.profile_id)
                    .map(|p| {
                        p.settings.set(&key, &value);
                        p.settings.clone()
                    });
                if let Some(np) = new_prefs {
                    let _ = a.registry.save(&a.data_dir);
                    a.prefs = np;
                }
            }
            Ok(Value::Null)
        }
        "prefs.get" => {
            let mut values = serde_json::Map::new();
            for (k, v) in &a.prefs.map {
                values.insert(k.clone(), Value::String(v.clone()));
            }
            values.insert("dns_status".into(), Value::String(
                rbcore::dns::effective_status(a.prefs.dns_mode(), &a.prefs.doh_url(), &a.prefs.dot_hostname()).as_str().into(),
            ));
            Ok(json!({
                "values": values,
                "themes": crate_themes(),
                "engines": rbcore::search::ENGINES.iter().map(|e| e.id).collect::<Vec<_>>(),
            }))
        }
        "theme.apply" => {
            let id = params.get("id").and_then(|x| x.as_str()).unwrap_or("obsidian");
            if let Some(p) = a.registry.get_mut(&a.profile_id) {
                p.theme_json = rbcore::theme::snapshot_json(id);
                let _ = a.registry.save(&a.data_dir);
            }
            a.push_chrome("theme", theme_json(a));
            Ok(Value::Null)
        }
        "theme.list" => Ok(json!({
            "themes": crate_themes(),
            "current": rbcore::theme::snapshot_id(
                &a.registry.get(&a.profile_id).map(|p| p.theme_json.clone()).unwrap_or_default()
            ),
        })),
        "profiles.list" => {
            let rows: Vec<Value> = a
                .registry
                .profiles
                .iter()
                .map(|p| json!({
                    "id": p.id, "name": p.name, "icon": p.icon,
                    "is_default": p.is_default, "active": p.id == a.profile_id,
                    "last_active": chrono::DateTime::from_timestamp_millis(p.last_active_at)
                        .map(|d| d.format("%Y-%m-%d").to_string()).unwrap_or_default(),
                }))
                .collect();
            Ok(json!({"rows": rows}))
        }
        "profile.add" => {
            let name = params.get("name").and_then(|x| x.as_str()).unwrap_or("New profile");
            let id = a.registry.add(name, "👤")?;
            rbcore::paths::ensure_profile_tree(&a.data_dir, &id).ok();
            let _ = a.registry.save(&a.data_dir);
            Ok(json!({"id": id}))
        }
        "profile.switch" => {
            // The engine context is per-profile; a switch restarts the app
            // (the same protocol the Android edition follows).
            let id = params.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            {
                let urls: Vec<String> = a.tabs.iter().map(|t| t.url.clone()).collect();
                let active = a.active.max(0) as usize;
                if let Some(p) = a.registry.get_mut(&id) {
                    p.settings.set_session(&urls, active);
                    p.last_active_at = now_ms();
                }
            }
            let _ = a.registry.save(&a.data_dir);
            let _ = a.downloads.reconcile(&a.profile_id, "Profile switched", now_ms());
            let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
            let exe = std::env::current_exe().map(|p| p.to_path_buf()).ok();
            let data_dir = a.data_dir.clone();
            drop(a);
            // Record the target profile, then relaunch.
            {
                let mut reg = ProfileRegistry::load(&data_dir);
                reg.active = Some(id);
                let _ = reg.save(&data_dir);
            }
            if let Some(exe) = exe {
                let _ = std::process::Command::new(exe).spawn();
            }
            std::process::exit(0);
        }
        "downloads.list" => {
            let rows: Vec<Value> = a
                .downloads
                .for_profile(&a.profile_id)
                .iter()
                .map(|d| {
                    let status_line = match d.status {
                        rbcore::downloads::Status::Completed => format!(
                            "{} — {}",
                            rbcore::downloads::format_bytes(d.total_bytes),
                            chrono::DateTime::from_timestamp_millis(d.completed_at)
                                .map(|x| x.format("%Y-%m-%d %H:%M").to_string()).unwrap_or_default()
                        ),
                        rbcore::downloads::Status::Failed => format!("failed — {}", d.error),
                        _ => {
                            if d.total_bytes > 0 {
                                format!("{} / {}", rbcore::downloads::format_bytes(d.downloaded_bytes), rbcore::downloads::format_bytes(d.total_bytes))
                            } else {
                                format!("{} so far", rbcore::downloads::format_bytes(d.downloaded_bytes))
                            }
                        }
                    };
                    json!({"id": d.id, "file_name": d.file_name, "status_line": status_line})
                })
                .collect();
            Ok(json!({"rows": rows}))
        }
        "download.open" => {
            let id = params.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            if let Some(d) = a.downloads.items.iter().find(|d| d.id == id) {
                if !d.destination.is_empty() {
                    let _ = std::process::Command::new("explorer").arg(&d.destination).spawn();
                }
            }
            Ok(Value::Null)
        }
        "download.remove" => {
            let id = params.get("id").and_then(|x| x.as_i64()).unwrap_or(0);
            a.downloads.remove(id);
            let _ = a.downloads.save(&rbcore::paths::downloads_path(&a.data_dir));
            Ok(Value::Null)
        }
        "notes.list" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let notes = rbcore::notes::Notes::load(&path);
            Ok(json!({"rows": notes.display_order().iter().map(|n| json!({"id": n.id, "title": n.title, "body": n.body})).collect::<Vec<_>>()}))
        }
        "notes.add" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            let id = notes.add(
                params.get("title").and_then(|x| x.as_str()).unwrap_or(""),
                params.get("body").and_then(|x| x.as_str()).unwrap_or(""),
                now_ms(),
            );
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(json!({"id": id}))
        }
        "notes.update" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            notes.edit(
                params.get("id").and_then(|x| x.as_i64()).unwrap_or(0),
                params.get("title").and_then(|x| x.as_str()).unwrap_or(""),
                params.get("body").and_then(|x| x.as_str()).unwrap_or(""),
                now_ms(),
            );
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "notes.delete" => {
            let path = rbcore::paths::notes_paths(&a.data_dir, &a.profile_id)[0].clone();
            let mut notes = rbcore::notes::Notes::load(&path);
            notes.remove(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0));
            notes.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "totp.list" => {
            let t = rbcore::totp::TotpStore::load_first(&a.data_dir, &a.profile_id);
            Ok(json!({"rows": t.items.iter().map(|x| json!({"id": x.id, "issuer": x.issuer, "label": x.label})).collect::<Vec<_>>()}))
        }
        "totp.add" => {
            let uri = params.get("uri").and_then(|x| x.as_str()).unwrap_or("");
            match rbcore::totp::parse_otpauth(uri) {
                Some((issuer, label, secret, algo, digits, period)) => {
                    let path = rbcore::paths::totp_path_for_rw(&a.data_dir, &a.profile_id);
                    let mut t = rbcore::totp::TotpStore::load(&path);
                    let id = t.add(&issuer, &label, &secret, algo, digits, period, now_ms());
                    t.save(&path).map_err(|e| e.to_string())?;
                    Ok(json!({"id": id}))
                }
                None => Ok(json!({"error": "not a valid otpauth:// URI"})),
            }
        }
        "totp.code" => {
            let t = rbcore::totp::TotpStore::load_first(&a.data_dir, &a.profile_id);
            Ok(json!({"code": t.code(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0), now_ms()).unwrap_or_default()}))
        }
        "totp.delete" => {
            let path = rbcore::paths::totp_path_for_rw(&a.data_dir, &a.profile_id);
            let mut t = rbcore::totp::TotpStore::load(&path);
            t.remove(params.get("id").and_then(|x| x.as_i64()).unwrap_or(0));
            t.save(&path).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.list" => Ok(json!({"rows": a.vault.items.iter().map(|c| json!({"id": c.id, "username": c.username, "domain": c.domain})).collect::<Vec<_>>()})),
        "vault.delete" => {
            let id = params.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            a.vault.remove(&id);
            a.vault.save(&a.vault_path()).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.save" => {
            let host = params.get("host").and_then(|x| x.as_str()).unwrap_or("");
            let username = params.get("username").and_then(|x| x.as_str()).unwrap_or("");
            let password = params.get("password").and_then(|x| x.as_str()).unwrap_or("");
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            if pass.is_empty() {
                return Ok(json!({"error": "set a master passphrase first (Passwords window)"}));
            }
            let enc = rbcore::vaultcrypto::seal_string(password, &pass)?;
            let domain = rbcore::credentials::canonical_host(host);
            a.vault.add(&a.profile_id, &domain, username, &enc, "", now_ms());
            a.vault.save(&a.vault_path()).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "vault.fill" => {
            let username = params.get("username").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let host = params.get("host").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            let entry = a.vault.for_host(&host).into_iter().find(|c| c.username == username).cloned();
            if let Some(entry) = entry {
                if let Ok(password) = rbcore::vaultcrypto::open_string(&entry.password_enc, &pass) {
                    let js = format!(
                        "window.__roomVaultFill && window.__roomVaultFill({}, {})",
                        serde_json::to_string(&username).unwrap(),
                        serde_json::to_string(&password).unwrap()
                    );
                    drop(a);
                    let app2 = app.clone();
                    let wv = {
                        let a = app2.borrow();
                        let a = a.as_ref().ok_or("no tab")?;
                        a.active_webview().cloned().ok_or("no tab")?
                    };
                    execute_script_then(&wv, &js, Box::new(move |r| {
                        let a = app2.borrow();
                        if let Some(a) = a.as_ref() {
                            a.reply(id, r.is_ok(), Value::Null);
                        }
                    }));
                    return Ok(Value::Null);
                }
            }
            Ok(Value::Null)
        }
        "wallet.state" => Ok(rbcore::wallet::load_wallet(&a.data_dir, &a.profile_id)
            .map(|w| json!({"locked": false, "exists": true, "accounts": w.accounts.iter().map(|x| json!({"chain": x.chain.as_str(), "address": x.address, "path": x.path})).collect::<Vec<_>>()}))
            .unwrap_or(json!({"locked": false, "exists": false, "accounts": []}))),
        "wallet.create" => {
            let mnemonic = rbcore::wallet::bip39::generate_mnemonic();
            let pass = a.vault_passphrase.clone().unwrap_or_default();
            if pass.is_empty() {
                return Ok(json!({"error": "set a master passphrase first"}));
            }
            let w = rbcore::wallet::create_wallet(&a.data_dir, &a.profile_id, "Room Wallet", &mnemonic, &pass, now_ms())?;
            Ok(json!({"mnemonic": mnemonic, "accounts": w.accounts.len()}))
        }
        "wallet.unlock" => {
            let passphrase = params.get("passphrase").and_then(|x| x.as_str()).unwrap_or("");
            if let Some(w) = rbcore::wallet::load_wallet(&a.data_dir, &a.profile_id) {
                match rbcore::wallet::open_mnemonic(&w, passphrase) {
                    Ok(_) => {
                        a.vault_passphrase = Some(passphrase.to_string());
                        Ok(json!({"ok": true}))
                    }
                    Err(e) => Ok(json!({"ok": false, "error": e})),
                }
            } else {
                a.vault_passphrase = Some(passphrase.to_string());
                Ok(json!({"ok": true}))
            }
        }
        "localai.status" => {
            let host = "http://127.0.0.1:11434";
            let agent = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(2)).build();
            let version: Option<Value> = agent.get(&format!("{host}/api/version")).call().ok().and_then(|r| r.into_json::<Value>().ok());
            let models: Vec<String> = agent
                .get(&format!("{host}/api/tags"))
                .call()
                .ok()
                .and_then(|r| r.into_json::<Value>().ok())
                .map(|v: Value| {
                    v.get("models")
                        .and_then(|m| m.as_array())
                        .map(|arr| arr.iter().filter_map(|m| m.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
                        .unwrap_or_default()
                })
                .unwrap_or_default();
            Ok(json!({"connected": version.is_some(), "models": models, "version": version.map(|v| v.get("version").and_then(|x| x.as_str()).unwrap_or("").to_string()).unwrap_or_default()}))
        }
        "localai.pull" => {
            let model = params.get("model").and_then(|x| x.as_str()).unwrap_or("").to_string();
            std::thread::spawn(move || {
                let _ = ureq::post("http://127.0.0.1:11434/api/pull").send_json(json!({"name": model}));
            });
            Ok(json!({"done": true}))
        }
        "qr.png" => {
            let text = params.get("text").and_then(|x| x.as_str()).unwrap_or("");
            let bytes = rbcore::qr::png(text, 6)?;
            Ok(json!({"base64": base64(&bytes)}))
        }
        "about" => Ok(json!({
            "version": rbcore::version(),
            "engine": "WebView2 (Chromium) on Windows",
            "note": "The Rust desktop edition of Room Browser.",
        })),
        "devtools.status" => Ok(json!({"notes": rbcore::devtools::edition_notes("webview2")})),
        "devtools.panel" => {
            let panel = params.get("panel").and_then(|x| x.as_str()).unwrap_or("OVERVIEW").to_string();
            if panel == "OVERVIEW" {
                return Ok(json!({"text": rbcore::devtools::edition_notes("webview2").join("\n")}));
            }
            let js = match panel.as_str() {
                "CONSOLE" => "(window.__roomConsole ? window.__roomConsole.read() : '[]')".to_string(),
                "NETWORK" => rbcore::bridge::NETWORK_PROBE_JS.to_string(),
                _ => rbcore::bridge::SECURITY_PROBE_JS.to_string(),
            };
            drop(a);
            let app2 = app.clone();
            let wv = {
                let a = app2.borrow();
                let a = a.as_ref().ok_or("no tab")?;
                a.active_webview().cloned().ok_or("no tab")?
            };
            execute_script_then(&wv, &js, Box::new(move |r| {
                let text = r
                    .map(|raw| {
                        let unescaped: String = serde_json::from_str(raw.trim_matches('"')).unwrap_or(raw.clone());
                        rbcore::redaction::redact(&unescaped)
                    })
                    .unwrap_or_default();
                let a = app2.borrow();
                if let Some(a) = a.as_ref() {
                    a.reply(id, true, json!({"text": text}));
                }
            }));
            Ok(Value::Null)
        }
        "data.clear" => {
            if let Some(wv) = a.active_webview().cloned() {
                execute_script_then(&wv, "(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}return 'cleared'})()", Box::new(|_| {}));
            }
            a.history.clear();
            let _ = rbcore::history::save(&rbcore::paths::history_path(&a.data_dir), &a.history);
            Ok(Value::Null)
        }
        "shield.read" => {
            let host = a.tabs.get(a.active as usize).and_then(|t| rbcore::urlintel::host_of(&t.url)).unwrap_or_default();
            Ok(json!({
                "host": host,
                "ads": a.filter.stat_blocked(rbcore::filters::Category::Ad),
                "trackers": a.filter.stat_blocked(rbcore::filters::Category::Tracker),
                "malicious": a.filter.stat_blocked(rbcore::filters::Category::Malicious),
            }))
        }
        "agent.clear" => {
            a.agent_sessions = rbcore::agent::sessions::AgentSessions::load(&a.data_dir, &a.profile_id);
            Ok(Value::Null)
        }
        "agent.send" => {
            if a.agent_busy {
                return Ok(json!({"error": "a turn is already running"}));
            }
            a.agent_busy = true;
            let text = params.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let mode = params.get("mode").and_then(|x| x.as_str()).unwrap_or("ASK").to_string();
            let session_id = a.agent_sessions.open_session(&a.profile_id, a.active, "ollama", "", now_ms());
            a.agent_sessions.append(&session_id, "user", &text, "", "", "", now_ms());
            let data_dir = a.data_dir.clone();
            let profile_id = a.profile_id.clone();
            let history: Vec<ChatMessage> = a
                .agent_sessions
                .messages_of(&session_id)
                .iter()
                .map(|m| ChatMessage::user(&m.content))
                .collect();
            drop(a);
            let proxy_owned = proxy.clone();
            std::thread::spawn(move || {
                agent_turn(data_dir, profile_id, session_id, text, mode, history, proxy_owned.clone());
                let _ = proxy_owned.send_event(Msg::AgentDone);
            });
            Ok(json!({"ok": true}))
        }
        _ => Err(format!("unknown method: {method}")),
    }
}

fn base64(data: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

fn crate_themes() -> Vec<Value> {
    rbcore::theme::THEMES
        .iter()
        .map(|t| {
            json!({
                "id": t.id, "name": t.name,
                "dark_primary": format!("#{:08X}", t.dark.0[3]),
                "dark_background": format!("#{:08X}", t.dark.0[0]),
            })
        })
        .collect()
}

fn theme_json(a: &App) -> Value {
    let theme_json_str = a.registry.get(&a.profile_id).map(|p| p.theme_json.clone()).unwrap_or_default();
    let id = rbcore::theme::snapshot_id(&theme_json_str).unwrap_or_else(|| "obsidian".into());
    let t = rbcore::theme::theme(&id).unwrap_or(rbcore::theme::default_theme());
    let pal = rbcore::theme::palette_for(t, &a.prefs.theme_mode());
    let mut m = serde_json::Map::new();
    for (i, n) in rbcore::theme::TOKENS.iter().enumerate() {
        m.insert(n.to_string(), Value::String(format!("#{:08X}", pal.0[i])));
    }
    Value::Object(m)
}

// ------------------------------------------------------------------
// The agent worker thread

/// The agent's view of the frontend: every page operation round-trips to
/// the UI thread through the event loop proxy.
struct FrontendToolHost {
    proxy: EventLoopProxy<Msg>,
    url: std::sync::Mutex<String>,
    yolo: bool,
}

fn page_op_blocking(proxy: &EventLoopProxy<Msg>, op: PageOp) -> CoreResult<String> {
    let (tx, rx) = channel();
    proxy.send_event(Msg::PageOp { reply: tx, op }).map_err(|e| format!("ui thread gone: {e}"))?;
    rx.recv_timeout(std::time::Duration::from_secs(90)).map_err(|e| format!("page op timeout: {e}"))?
}

impl ToolHost for FrontendToolHost {
    fn run_js(&mut self, js: &str) -> CoreResult<String> {
        page_op_blocking(&self.proxy, PageOp::RunJs { js: js.to_string() })
    }
    fn navigate(&mut self, url: &str) -> CoreResult<String> {
        page_op_blocking(&self.proxy, PageOp::Navigate { url: url.to_string() })
    }
    fn go_back(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.proxy, PageOp::Back).map(|_| ())
    }
    fn go_forward(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.proxy, PageOp::Forward).map(|_| ())
    }
    fn reload(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.proxy, PageOp::Reload).map(|_| ())
    }
    fn current_url(&mut self) -> String {
        self.url.lock().unwrap().clone()
    }
    fn current_title(&mut self) -> String {
        String::new()
    }
    fn screenshot(&mut self) -> CoreResult<Vec<u8>> {
        Err("this engine build cannot capture screenshots; read_page carries the page".into())
    }
    fn open_new_tab(&mut self, url: &str) -> CoreResult<usize> {
        let r = page_op_blocking(&self.proxy, PageOp::NewTab { url: url.to_string() })?;
        r.parse::<usize>().map_err(|e: std::num::ParseIntError| e.to_string())
    }
    fn list_tabs(&mut self) -> Vec<(usize, String, String)> {
        match page_op_blocking(&self.proxy, PageOp::ListTabs) {
            Ok(s) => serde_json::from_str(&s).unwrap_or_default(),
            Err(_) => Vec::new(),
        }
    }
    fn switch_tab(&mut self, index: usize) -> CoreResult<()> {
        page_op_blocking(&self.proxy, PageOp::SwitchTab { index }).map(|_| ())
    }
    fn close_tab(&mut self) -> CoreResult<()> {
        page_op_blocking(&self.proxy, PageOp::CloseTab).map(|_| ())
    }
    fn open_screen(&mut self, screen: &str) -> CoreResult<String> {
        Ok(format!("opened {screen}"))
    }
    fn download(&mut self, url: &str, name: &str) -> CoreResult<String> {
        page_op_blocking(&self.proxy, PageOp::Download { url: url.to_string(), name: name.to_string() })
    }
    fn save_pdf(&mut self, _name: &str) -> CoreResult<String> {
        Err("PDF capture is not available through this engine yet".into())
    }
    fn clipboard_copy(&mut self, _text: &str) -> CoreResult<()> {
        Ok(())
    }
    fn vault_passphrase(&mut self) -> Option<String> {
        None
    }
    fn wallet_state(&mut self) -> Value {
        json!({"locked": true})
    }
    fn wallet_request_summary(&mut self) -> Value {
        json!([])
    }
    fn wallet_approve(&mut self, _id: &str) -> CoreResult<String> {
        Err("no pending wallet requests".into())
    }
    fn wallet_reject(&mut self, _id: &str) -> CoreResult<String> {
        Ok("rejected".into())
    }
    fn wallet_switch_network(&mut self, _id: &str) -> CoreResult<String> {
        Err("network switching runs from the Wallet window".into())
    }
    fn node_run(&mut self, script: &str) -> CoreResult<String> {
        rbcore::node::run(&rbcore::paths::data_dir(), script, rbcore::node::DEFAULT_BUDGET_MS)
    }
    fn node_packages(&mut self, action: &str, name: &str, spec: &str) -> CoreResult<String> {
        rbcore::node::packages(&rbcore::paths::data_dir(), action, name, spec)
    }
    fn confirm(&mut self, _prompt: &str) -> bool {
        // Interactive confirmations arrive through the chrome; YOLO is the
        // only mode that proceeds without an answer.
        self.yolo
    }
    fn site_permissions(&mut self) -> Vec<Value> {
        Vec::new()
    }
    fn set_site_permission(&mut self, _host: &str, _permission: &str, _decision: &str) -> CoreResult<()> {
        Ok(())
    }
    fn page_host(&mut self) -> String {
        rbcore::urlintel::host_of(&self.url.lock().unwrap().clone()).unwrap_or_default()
    }
}

struct AgentLoopHost {
    executor: Executor,
    proxy: EventLoopProxy<Msg>,
    session: String,
    dir: PathBuf,
    profile: String,
    tab_url: std::sync::Arc<std::sync::Mutex<String>>,
}

impl LoopHost for AgentLoopHost {
    fn execute_tool(&mut self, call: &ToolCall) -> String {
        *self.tab_url.lock().unwrap() = self.executor.host_mut().current_url();
        let r = self.executor.execute(call);
        let _ = (&self.session, &self.dir, &self.profile);
        r
    }
    fn emit(&mut self, event: AgentEvent) {
        let (name, payload, persist) = match &event {
            AgentEvent::AssistantText(t) => ("agent.text", json!({"text": t}), Some(("assistant", t.clone()))),
            AgentEvent::AssistantThinking(t) => ("agent.thinking", json!({"text": t}), None),
            AgentEvent::ToolStarted { name, args } => ("agent.tool", json!({"name": name, "args": args, "result": ""}), None),
            AgentEvent::ToolFinished { name, result } => ("agent.tool", json!({"name": name, "args": null, "result": result}), Some(("assistant", result.clone()))),
            AgentEvent::PlanUpdated(p) => ("agent.plan", p.clone(), None),
            AgentEvent::FinalAnswer(t) => ("agent.text", json!({"text": t}), Some(("assistant", t.clone()))),
            AgentEvent::AgentError(t) => ("agent.error", json!({"text": t}), None),
            AgentEvent::Notice(t) => ("agent.notice", json!({"text": t}), None),
        };
        if let Some((role, content)) = persist {
            let mut sessions = rbcore::agent::sessions::AgentSessions::load(&self.dir, &self.profile);
            sessions.append(&self.session, &role, &content, "", "", "", now_ms());
            let _ = sessions.save(&self.dir, &self.profile);
        }
        let _ = self.proxy.send_event(Msg::Chrome {
            json: json!({"name": name, "payload": payload}),
        });
    }
}

fn agent_turn(
    data_dir: PathBuf,
    profile_id: String,
    session_id: String,
    text: String,
    mode: String,
    history: Vec<ChatMessage>,
    proxy: EventLoopProxy<Msg>,
) {
    let push = |name: &str, payload: Value| {
        let _ = proxy.send_event(Msg::Chrome { json: json!({"name": name, "payload": payload}) });
    };

    // Provider: the local Ollama daemon when reachable.
    let base = "http://127.0.0.1:11434";
    let agent = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(3)).build();
    let model = agent
        .get(&format!("{base}/api/tags"))
        .call()
        .ok()
        .and_then(|r| r.into_json::<Value>().ok())
        .and_then(|v: Value| {
            v.get("models")
                .and_then(|m| m.as_array())
                .and_then(|a| a.first())
                .and_then(|m| m.get("name"))
                .and_then(|n| n.as_str())
                .map(String::from)
        })
        .unwrap_or_else(|| "llama3.2".into());

    push("agent.model", json!({"model": model}));
    let mut gateway = rbcore::agent::gateway::OllamaGateway {
        base_url: base.to_string(),
        api_key: String::new(),
        model: model.clone(),
        num_ctx: 4096,
        keep_alive_minutes: 10,
    };

    let mut messages = vec![ChatMessage::system(&format!(
        "You are the Room Browser agent on a desktop computer. Today is {}. Use the tools to drive the page; answer plainly only when the task list is finished.{}",
        chrono::Utc::now().format("%Y-%m-%d"),
        if mode == "PLAN" { " Plan mode: produce a plan first; page actions are refused until the user approves." } else { "" }
    ))];
    messages.extend(history.iter().cloned());

    let executor = Executor {
        host: Box::new(FrontendToolHost {
            proxy: proxy.clone(),
            url: std::sync::Mutex::new(String::new()),
            yolo: mode == "YOLO",
        }),
        stores: AppStores::new(data_dir.clone(), &profile_id),
        gate_ctx: GateContext {
            plan_mode: mode == "PLAN",
            withheld_permissions: Vec::new(),
            run_kind: rbcore::agent::gates::RunKind::Interactive,
            task_allow_post: false,
            task_profile_tools: false,
        },
        budget: rbcore::agent::gates::ActionBudget::default(),
        devtools_available: true,
        diagnostics_enabled: false,
    };
    let mut host = AgentLoopHost {
        executor,
        proxy: proxy.clone(),
        session: session_id,
        dir: data_dir.clone(),
        profile: profile_id.clone(),
        tab_url: std::sync::Arc::new(std::sync::Mutex::new(String::new())),
    };

    let mut plan = AgentPlan::default();
    let config = AgentConfig::default();
    match rbcore::agent::loop_runner::run_turn(&mut gateway, &mut host, &config, &mut plan, &mut messages) {
        Ok(_) => {}
        Err(e) => {
            host.emit(AgentEvent::AgentError(e));
        }
    }
}
