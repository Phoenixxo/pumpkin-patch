//! `bench:hud`. A representative active HUD component for benchmarks: it subscribes to ticks,
//! reads the player through `view`, and redraws a small panel every tick.

use std::cell::RefCell;

use pumpkin_client_sdk::{
    DrawCommand, Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, RectCmd, TextCmd,
    export, guest::Guest, view,
};

#[derive(Default)]
struct State {
    tick: u64,
    slot: i32,
    pos: String,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
}

struct Bench;

impl Guest for Bench {
    fn init(info: InitInfo) -> Result<InitResult, String> {
        STATE.with_borrow_mut(|s| s.slot = (info.session.session_id % 7) as i32);
        Ok(InitResult { subscriptions: EventKinds::TICK })
    }

    fn update(events: Vec<Event>, frame: FrameInfo) -> FrameOutput {
        Self::handle_events(events);
        Self::render(frame)
    }

    fn shutdown() {}
}

impl Bench {
    fn handle_events(events: Vec<Event>) {
        STATE.with_borrow_mut(|s| {
            for event in events {
                if let Event::Tick(t) = event {
                    s.tick = t.game_tick;
                }
            }
            s.pos = match view::local_player() {
                Ok(p) => format!("{:.1} {:.1} {:.1}", p.pos.x, p.pos.y, p.pos.z),
                Err(e) => format!("{e:?}"),
            };
        });
    }

    fn render(_: FrameInfo) -> FrameOutput {
        STATE.with_borrow(|s| {
            let y = 40 + 12 * s.slot;
            FrameOutput::Commands(vec![
                DrawCommand::FillRect(RectCmd { x: 2, y, w: 150, h: 11, argb: 0x6000_0000 }),
                DrawCommand::Text(TextCmd {
                    x: 4,
                    y: y + 1,
                    text: format!("bench t={} {}", s.tick, s.pos),
                    argb: 0xFF55_FF55,
                    shadow: false,
                }),
            ])
        })
    }
}

export!(Bench);
