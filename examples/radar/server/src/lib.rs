//! Server component of `example:radar`.
//!
//! Stores waypoints per dimension, sends each player the waypoints of their world, and broadcasts
//! the positions of every player in a world on a fixed period. Messages travel over Pumpkin's
//! `pumpkin:mux` endpoint through the virtual channels `pumpkin:mux/example:radar/<channel>`.
//! Every broadcast carries the server's tick time and this plugin's own handler timings.

use std::{
    collections::HashMap,
    sync::{
        Mutex,
        atomic::{AtomicU32, Ordering},
    },
    time::Instant,
};

use pumpkin_plugin_api::{
    Context, EntityType, Player, Plugin, PluginMetadata, Server,
    events::{EventData, EventHandler, EventPriority, PlayerChangeWorldEvent, PlayerCustomPayloadEvent},
    scheduler::schedule_repeating_task,
};
use radar_protocol::{Bench, Down, RemotePlayer, ServerStats, Up, Waypoint};
use tracing::{info, warn};

const PREFIX: &str = "pumpkin:mux/example:radar/";
const MAX_WAYPOINTS_PER_WORLD: usize = 1000;
const MAX_BENCH_ENTITIES: u32 = 512;

#[derive(Default)]
struct State {
    waypoints: HashMap<String, Vec<Waypoint>>,
    next_id: u32,
    /// Benchmark pigs, by dimension and entity uuid.
    bench_entities: Vec<(String, u64, u64)>,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);
static PERIOD: AtomicU32 = AtomicU32::new(20);
static TICK: AtomicU32 = AtomicU32::new(0);
static SEQ: AtomicU32 = AtomicU32::new(0);
static LAST_HANDLER_US: AtomicU32 = AtomicU32::new(0);
static LAST_BROADCAST_US: AtomicU32 = AtomicU32::new(0);

fn with_state<T>(f: impl FnOnce(&mut State) -> T) -> T {
    let mut guard = STATE.lock().unwrap_or_else(|e| e.into_inner());
    f(guard.get_or_insert_with(State::default))
}

fn send(player: &Player, message: &Down) {
    let (channel, bytes) = message.encode();
    if let Some(java) = player.as_java() {
        java.send_custom_payload(&format!("{PREFIX}{channel}"), &bytes);
    }
}

fn waypoints_for(dimension: &str) -> Down {
    let list = with_state(|s| s.waypoints.get(dimension).cloned().unwrap_or_default());
    Down::Waypoints { dimension: dimension.to_string(), list }
}

fn broadcast_waypoints(server: &Server, player: &Player) {
    let world = player.get_world();
    let message = waypoints_for(&world.get_dimension());
    for p in server.get_players_in_world(world) {
        send(&p, &message);
    }
}

fn stats(server: &Server) -> ServerStats {
    ServerStats {
        mspt: server.get_mspt() as f32,
        handler_micros: LAST_HANDLER_US.load(Ordering::Relaxed),
        broadcast_micros: LAST_BROADCAST_US.load(Ordering::Relaxed),
    }
}

fn is_operator(player: &Player) -> bool {
    use pumpkin_plugin_api::permission::PermissionLevel;
    !matches!(player.get_permission_level(), PermissionLevel::Zero | PermissionLevel::One)
}

fn bench(server: &Server, player: &Player, request: Bench) -> String {
    let world = player.get_world();
    let dimension = world.get_dimension();
    match request {
        Bench::Entities(count) => {
            let count = count.min(MAX_BENCH_ENTITIES);
            let old = with_state(|s| std::mem::take(&mut s.bench_entities));
            let mut removed = 0;
            for w in server.get_all_worlds() {
                let dim = w.get_dimension();
                for e in w.get_entities() {
                    let id = e.get_uuid();
                    if old.iter().any(|(d, hi, lo)| *d == dim && *hi == id.high && *lo == id.low) {
                        e.remove();
                        removed += 1;
                    }
                }
            }
            // A square grid centred on the player, 2 blocks apart, AI off so nothing moves.
            let (px, py, pz) = player.get_position();
            let side = (f64::from(count)).sqrt().ceil() as u32;
            let mut spawned = Vec::new();
            for i in 0..count {
                let x = px + f64::from(i % side) * 2.0 - f64::from(side);
                let z = pz + f64::from(i / side) * 2.0 - f64::from(side);
                let entity = world.spawn_entity(EntityType::Pig, (x.floor() + 0.5, py, z.floor() + 0.5));
                if let Some(mob) = entity.as_mob() {
                    mob.set_ai_disabled(true);
                }
                let id = entity.get_uuid();
                spawned.push((dimension.clone(), id.high, id.low));
            }
            with_state(|s| s.bench_entities = spawned);
            format!("bench: {count} pigs (removed {removed})")
        }
        Bench::Waypoints(count) => {
            let count = (count as usize).min(MAX_WAYPOINTS_PER_WORLD);
            let (px, py, pz) = player.get_position();
            with_state(|s| {
                let list = s.waypoints.entry(dimension.clone()).or_default();
                list.retain(|w| w.owner != "bench");
                for i in 0..count {
                    let angle = i as f64 * 2.399_963; // golden angle spreads them evenly
                    let r = 8.0 + (i as f64).sqrt() * 6.0;
                    s.next_id += 1;
                    list.push(Waypoint {
                        id: s.next_id,
                        owner: "bench".into(),
                        name: format!("B{i}"),
                        pos: [px + r * angle.cos(), py, pz + r * angle.sin()],
                    });
                }
            });
            broadcast_waypoints(server, player);
            format!("bench: {count} waypoints")
        }
        Bench::BroadcastPeriod(period) => {
            PERIOD.store(period.clamp(1, 200), Ordering::Relaxed);
            format!("bench: broadcast every {} ticks", period.clamp(1, 200))
        }
    }
}

struct PayloadHandler;

impl EventHandler<PlayerCustomPayloadEvent> for PayloadHandler {
    fn handle(&self, server: Server, event: EventData<PlayerCustomPayloadEvent>) -> EventData<PlayerCustomPayloadEvent> {
        let Some(channel) = event.channel.strip_prefix(PREFIX) else {
            return event;
        };
        let start = Instant::now();
        let player = &event.player;
        match Up::decode(channel, &event.data) {
            Ok(Up::Sync) => {
                send(player, &waypoints_for(&player.get_world().get_dimension()));
            }
            Ok(Up::AddWaypoint { name, pos }) => {
                let dimension = player.get_world().get_dimension();
                let owner = player.get_name();
                let added = with_state(|s| {
                    s.next_id += 1;
                    let id = s.next_id;
                    let list = s.waypoints.entry(dimension).or_default();
                    if list.len() >= MAX_WAYPOINTS_PER_WORLD {
                        return false;
                    }
                    list.push(Waypoint { id, owner, name: name.chars().take(32).collect(), pos });
                    true
                });
                if added {
                    broadcast_waypoints(&server, player);
                } else {
                    send(player, &Down::Notice("waypoint limit reached".into()));
                }
            }
            Ok(Up::ClearWaypoints) => {
                let owner = player.get_name();
                with_state(|s| s.waypoints.values_mut().for_each(|l| l.retain(|w| w.owner != owner)));
                broadcast_waypoints(&server, player);
            }
            Ok(Up::Bench(request)) => {
                let notice = if is_operator(player) {
                    bench(&server, player, request)
                } else {
                    "bench controls need operator permission".into()
                };
                info!("{} {notice}", player.get_name());
                send(player, &Down::Notice(notice));
            }
            Err(_) => warn!("malformed radar message on {channel} from {}", player.get_name()),
        }
        LAST_HANDLER_US.store(start.elapsed().as_micros().min(u32::MAX as u128) as u32, Ordering::Relaxed);
        event
    }
}

struct WorldChangeHandler;

impl EventHandler<PlayerChangeWorldEvent> for WorldChangeHandler {
    fn handle(&self, _server: Server, event: EventData<PlayerChangeWorldEvent>) -> EventData<PlayerChangeWorldEvent> {
        send(&event.player, &waypoints_for(&event.new_world.get_dimension()));
        event
    }
}

/// Runs every tick and broadcasts player positions every `PERIOD` ticks.
fn broadcast_tick(server: Server) {
    let tick = TICK.fetch_add(1, Ordering::Relaxed);
    if tick % PERIOD.load(Ordering::Relaxed) != 0 {
        return;
    }
    let start = Instant::now();
    let seq = SEQ.fetch_add(1, Ordering::Relaxed);
    let stats = stats(&server);
    for world in server.get_all_worlds() {
        let dimension = world.get_dimension();
        let players = server.get_players_in_world(world);
        if players.is_empty() {
            continue;
        }
        let list: Vec<RemotePlayer> = players
            .iter()
            .map(|p| {
                let (x, y, z) = p.get_position();
                RemotePlayer { name: p.get_name(), pos: [x, y, z] }
            })
            .collect();
        for p in &players {
            // Each player's list leaves out the player it is sent to.
            let name = p.get_name();
            let others = list.iter().filter(|r| r.name != name).cloned().collect();
            send(p, &Down::Players { dimension: dimension.clone(), seq, stats, list: others });
        }
    }
    LAST_BROADCAST_US.store(start.elapsed().as_micros().min(u32::MAX as u128) as u32, Ordering::Relaxed);
}

struct RadarServer;

impl Plugin for RadarServer {
    fn new() -> Self {
        Self
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "example-radar".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Pumpkin Patch".into()],
            description: "Shared waypoints and far players for example:radar client components".into(),
            dependencies: Vec::new(),
            permissions: Vec::new(),
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        context.register_event_handler(PayloadHandler, EventPriority::Normal, true)?;
        context.register_event_handler(WorldChangeHandler, EventPriority::Normal, false)?;
        schedule_repeating_task(1, 1, broadcast_tick);
        info!("example:radar server component loaded");
        Ok(())
    }
}

pumpkin_plugin_api::register_plugin!(RadarServer);
