//! `bench:host-calls`. Makes 1000 calls to the cheapest host import on every tick, to measure the
//! cost of one guest-to-host crossing.

use pumpkin_client_sdk::{
    Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, export, guest::Guest, net,
};

const CALLS: u32 = 1000;

struct HostCalls;

impl Guest for HostCalls {
    fn init(_: InitInfo) -> Result<InitResult, String> {
        Ok(InitResult { subscriptions: EventKinds::TICK })
    }

    fn update(events: Vec<Event>, frame: FrameInfo) -> FrameOutput {
        Self::handle_events(events);
        Self::render(frame)
    }

    fn shutdown() {}
}

impl HostCalls {
    fn handle_events(events: Vec<Event>) {
        if events.iter().any(|e| matches!(e, Event::Tick(_))) {
            let mut sum = 0u32;
            for _ in 0..CALLS {
                sum = sum.wrapping_add(net::max_payload());
            }
            std::hint::black_box(sum);
        }
    }

    fn render(_: FrameInfo) -> FrameOutput {
        FrameOutput::Unchanged
    }
}

export!(HostCalls);
