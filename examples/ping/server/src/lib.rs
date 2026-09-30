//! Server component of `example:ping`.
//!
//! Pumpkin's `pumpkin:mux` endpoint delivers the client component's `request` channel as a custom
//! payload on the virtual channel `pumpkin:mux/example:ping/request`, and routes a payload sent on
//! `pumpkin:mux/example:ping/response` back to that player's client component.

use pumpkin_plugin_api::{
    Context, Plugin, PluginMetadata, Server,
    events::{EventData, EventHandler, EventPriority, PlayerCustomPayloadEvent},
};
use tracing::info;

const REQUEST: &str = "pumpkin:mux/example:ping/request";
const RESPONSE: &str = "pumpkin:mux/example:ping/response";

struct PingHandler;

impl EventHandler<PlayerCustomPayloadEvent> for PingHandler {
    fn handle(
        &self,
        server: Server,
        event: EventData<PlayerCustomPayloadEvent>,
    ) -> EventData<PlayerCustomPayloadEvent> {
        if event.channel != REQUEST || event.data.len() != 4 {
            return event;
        }
        let seq = u32::from_le_bytes([event.data[0], event.data[1], event.data[2], event.data[3]]);
        let player = &event.player;
        let (x, y, z) = player.get_position();
        let text = format!(
            "Hello {} from the Pumpkin server component!\nTPS {:.1}, {} online, you are at {:.0} {:.0} {:.0}",
            player.get_name(),
            server.get_tps(),
            server.get_player_count(),
            x,
            y,
            z
        );
        let mut reply = seq.to_le_bytes().to_vec();
        reply.extend_from_slice(text.as_bytes());
        if let Some(java) = player.as_java() {
            java.send_custom_payload(RESPONSE, &reply);
        }
        info!("answered ping #{seq} from {}", player.get_name());
        event
    }
}

struct PingServer;

impl Plugin for PingServer {
    fn new() -> Self {
        Self
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "example-ping".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Pumpkin Patch".into()],
            description: "Answers example:ping client components over pumpkin:mux".into(),
            dependencies: Vec::new(),
            permissions: Vec::new(),
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        context.register_event_handler(PingHandler, EventPriority::Normal, true)?;
        info!("example:ping server component loaded");
        Ok(())
    }
}

pumpkin_plugin_api::register_plugin!(PingServer);
