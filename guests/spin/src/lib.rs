//! `example:spin`. Loops forever when the `spin` action is pressed, to test the call budget.

use std::hint::black_box;

use pumpkin_client_sdk::{
    Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, export, guest::Guest, info,
};

struct Spin;

impl Guest for Spin {
    fn init(_: InitInfo) -> Result<InitResult, String> {
        Ok(InitResult { subscriptions: EventKinds::INPUT })
    }

    fn update(events: Vec<Event>, frame: FrameInfo) -> FrameOutput {
        Self::handle_events(events);
        Self::render(frame)
    }

    fn shutdown() {}
}

impl Spin {
    fn handle_events(events: Vec<Event>) {
        for event in events {
            if let Event::Action(a) = event
                && a.action == "spin"
                && a.pressed
            {
                info("spinning forever");
                let mut i: u64 = 0;
                loop {
                    i = black_box(i.wrapping_add(1));
                }
            }
        }
    }

    fn render(_: FrameInfo) -> FrameOutput {
        FrameOutput::Unchanged
    }
}

export!(Spin);
