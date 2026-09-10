use std::io::{self, Read, Write};

#[link(name = "kernel32")]
extern "system" {
    fn GetConsoleWindow() -> *mut std::ffi::c_void;
}

fn emit(value: &str) {
    println!("{value}");
}

fn main() {
    // 실제 Windows 자식 프로세스에서 콘솔 생성 여부를 검증합니다.
    unsafe {
        assert!(GetConsoleWindow().is_null(), "unexpected Claude console window");
    }
    let args: Vec<String> = std::env::args().collect();
    let mode = std::fs::read_to_string(std::env::var("LUMI_CLAUDE_TEST_MODE").unwrap()).unwrap_or_default();
    let mut log = std::fs::OpenOptions::new().create(true).append(true)
        .open(std::env::var("LUMI_CLAUDE_TEST_LOG").unwrap()).unwrap();
    writeln!(log, "{} {}", std::process::id(), args[1..].join(" ")).unwrap();
    if args.get(1).map(String::as_str) == Some("auth") {
        if args.get(2).map(String::as_str) == Some("logout") {
            if mode == "logout_error" { eprintln!("logout denied"); std::process::exit(1); }
            emit("{}");
            return;
        }
        match mode.as_str() {
            "auth_off" => { emit(r#"{"loggedIn":false}"#); std::process::exit(1); }
            "auth_bad" => { emit("invalid json"); return; }
            "auth_error" => { eprintln!("auth unavailable"); std::process::exit(2); }
            "auth_timeout" => std::thread::sleep(std::time::Duration::from_secs(45)),
            _ => {}
        }
        emit(r#"{"loggedIn":true}"#);
        return;
    }
    assert!(args.windows(2).any(|pair| pair == ["--output-format", "json"]));
    assert!(args.iter().any(|arg| arg == "--no-session-persistence"));
    assert!(args.windows(2).any(|pair| pair == ["--tools", ""]));
    let model = args.windows(2).find(|pair| pair[0] == "--model").unwrap()[1].clone();
    let mut prompt = String::new();
    io::stdin().read_to_string(&mut prompt).unwrap();
    assert!(prompt.contains("테스트"));
    match mode.as_str() {
        "limit" => emit(r#"{"result":"usage limit reached","is_error":true}"#),
        "model_error" => { emit(r#"{"result":"model unavailable","is_error":true}"#); std::process::exit(1); }
        "subtype_error" => emit(r#"{"subtype":"error_during_execution","errors":["permission denied"]}"#),
        "bad_json" => emit("not json"),
        "empty" => emit(r#"{"result":""}"#),
        "stderr_error" => { eprintln!("network unavailable"); std::process::exit(2); }
        _ => println!("{{\"result\":\"mock claude reply: {model}\",\"is_error\":false}}"),
    }
}
