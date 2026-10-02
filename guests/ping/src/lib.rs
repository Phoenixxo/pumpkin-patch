//! `example:ping`. Pressing the `ping` action asks the server for data, and the response is
//! drawn on the HUD.

use std::cell::RefCell;

use pumpkin_client_sdk::{
    DrawCommand, Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, RectCmd, TextCmd,
    export, guest::Guest, info, net,
};

#[derive(Default)]
struct State {
    next_seq: u32,
    sent: u32,
    lines: Vec<String>,
    dirty: bool,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
}

struct Ping;

impl Guest for Ping {
    fn init(init: InitInfo) -> Result<InitResult, String> {
        info(&format!(
            "ping {} started, session {}, negotiated={}",
            init.mod_version, init.session.session_id, init.session.net_negotiated
        ));
        STATE.with_borrow_mut(|s| {
            s.lines = vec![if init.session.net_negotiated {
                "Press O to ask the server".into()
            } else {
                "No Pumpkin server route".into()
            }];
            s.dirty = true;
        });
        Ok(InitResult { subscriptions: EventKinds::INPUT })
    }

    fn update(events: Vec<Event>, frame: FrameInfo) -> FrameOutput {
        Self::handle_events(events);
        Self::render(frame)
    }

    fn shutdown() {}
}

impl Ping {
    fn handle_events(events: Vec<Event>) {
        STATE.with_borrow_mut(|s| {
            for event in events {
                match event {
                    Event::Action(a) if a.action == "ping" && a.pressed => {
                        let seq = s.next_seq;
                        s.next_seq += 1;
                        match net::send("request", &seq.to_le_bytes()) {
                            Ok(()) => s.sent += 1,
                            Err(e) => {
                                s.lines = vec![format!("send failed: {e:?}")];
                                s.dirty = true;
                            }
                        }
                    }
                    Event::NetMessage(m) if m.channel == "response" && m.payload.len() >= 4 => {
                        let seq = u32::from_le_bytes([m.payload[0], m.payload[1], m.payload[2], m.payload[3]]);
                        let text = String::from_utf8_lossy(&m.payload[4..]).into_owned();
                        s.lines = vec![format!("Reply #{seq} ({} sent)", s.sent)];
                        s.lines.extend(text.lines().map(str::to_owned));
                        s.dirty = true;
                    }
                    _ => {}
                }
            }
        });
    }

    fn render(frame: FrameInfo) -> FrameOutput {
        STATE.with_borrow_mut(|s| {
            if !s.dirty {
                return FrameOutput::Unchanged;
            }
            s.dirty = false;
            let width = 220;
            let x = frame.gui_width as i32 - width - 4;
            let mut commands = vec![DrawCommand::FillRect(RectCmd {
                x: x - 3,
                y: 3,
                w: (width + 6) as u32,
                h: 14 + 10 * s.lines.len() as u32,
                argb: 0x9000_0000,
            })];
            commands.push(DrawCommand::Text(TextCmd {
                x,
                y: 6,
                text: "Pumpkin Patch: example:ping".into(),
                argb: 0xFFFF_AA00,
                shadow: true,
            }));
            for (i, line) in s.lines.iter().enumerate() {
                commands.push(DrawCommand::Text(TextCmd {
                    x,
                    y: 16 + 10 * i as i32,
                    text: line.clone(),
                    argb: 0xFFFF_FFFF,
                    shadow: true,
                }));
            }
            FrameOutput::Commands(commands)
        })
    }
}

export!(Ping);
