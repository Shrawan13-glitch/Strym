//! Local RTMP ingest server for development + e2e.
//!
//! Uses the core's own ingest server. Emulator clients publish to
//! `rtmp://10.0.2.2:1935/live/<key>`, physical devices to the host LAN IP.
//! The server runs until Ctrl-C.

use std::time::Duration;

use stream::rtmp::server::{MultiSinkHandler, RtmpServer, ServerConfig, SinkHandler};
use stream::sink::RecordingOutput;
use stream::transport::FileTransport;

fn main() {
    let addr = std::env::args().nth(1).unwrap_or_else(|| "0.0.0.0:1935".to_owned());
    let app = std::env::args().nth(2).unwrap_or_else(|| "live".to_owned());

    let server = RtmpServer::bind(&addr, ServerConfig { app: app.clone(), ..Default::default() })
        .expect("bind ingest server");
    println!(">> ingest listening on {addr} (app `{app}`); Ctrl-C to stop");

    loop {
        let session = match server.accept() {
            Ok(session) => session,
            Err(e) => {
                eprintln!("accept: {e}");
                continue;
            }
        };
        let key = session.key().to_owned();
        let app = session.app().to_owned();
        std::fs::create_dir_all("ingest").ok();
        let path = format!("ingest/{app}_{key}.flv");
        let file = std::fs::File::create(&path).expect("create flv file");
        let sink = RecordingOutput::new(FileTransport::new(file), Default::default());
        println!(">> publish: app={app} key={key} -> {path}");
        let handler = MultiSinkHandler::with(vec![SinkHandler::boxed(Box::new(sink))]);
        let _ = std::thread::spawn(move || {
            let _ = session.serve(handler);
            std::thread::sleep(Duration::from_millis(50));
        });
    }
}
