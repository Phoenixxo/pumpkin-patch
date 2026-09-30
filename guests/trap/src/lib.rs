//! `example:trap`. Shows a HUD line each tick and executes `unreachable` when the `trap` action
//! is pressed, so the host faults it while other components keep running.

use std::cell::Cell;

use pumpkin_client_sdk::{
    DrawCommand, Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, TextCmd, export,
    guest::Guest, info,
};

thread_local! {
    static TICKS: Cell<u64> = const { Cell::new(0) };
}

struct Trap;

impl Guest for Trap {
    fn init(_: InitInfo) -> Result<InitResult, String> {
        info("trap component armed; press the trap key to fault it");
        Ok(InitResult { subscriptions: EventKinds::TICK | EventKinds::INPUT })
    }

    fn handle_events(events: Vec<Event>) {
        for event in events {
            match event {
                Event::Tick(t) => TICKS.set(t.game_tick),
                Event::Action(a) if a.action == "trap" && a.pressed => {
                    info("trapping now");
                    core::arch::wasm32::unreachable();
                }
                _ => {}
            }
        }
    }

    fn render(frame: FrameInfo) -> FrameOutput {
        FrameOutput::Commands(vec![DrawCommand::Text(TextCmd {
            x: 4,
            y: frame.gui_height as i32 - 60,
            text: format!("example:trap alive at tick {} (press K to trap)", TICKS.get()),
            argb: 0xFFFF_5555,
            shadow: true,
        })])
    }

    fn shutdown() {}
}

export!(Trap);
