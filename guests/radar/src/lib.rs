//! `example:radar`, the client component.
//!
//! A radar in the top-right corner shows nearby entities from `view.nearby-entities`, waypoints
//! shared through the server, and players the server reports beyond render distance.
//! Keys: B adds a waypoint, N cycles the zoom, M toggles the radar, V clears your waypoints.

use std::cell::RefCell;

use pumpkin_client_sdk::{
    DrawCommand, Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult, RectCmd, TextCmd,
    export, guest::Guest, hud, info, net, view,
};
use radar_protocol::{Down, RemotePlayer, ServerStats, Up, Waypoint};

const ZOOMS: [f64; 3] = [16.0, 32.0, 64.0];
const SIZE: i32 = 96;
const MAX_ENTITIES: u32 = 256;
const LIST_ROWS: usize = 5;

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Hostile,
    Passive,
    Player,
    Item,
    Other,
}

impl Kind {
    fn of(kind: &str) -> Self {
        let k = kind.strip_prefix("minecraft:").unwrap_or(kind);
        match k {
            "player" => Self::Player,
            "item" | "experience_orb" => Self::Item,
            "zombie" | "skeleton" | "creeper" | "spider" | "cave_spider" | "enderman" | "witch" | "slime"
            | "husk" | "stray" | "drowned" | "phantom" | "blaze" | "ghast" | "magma_cube" | "piglin_brute"
            | "wither_skeleton" | "zombified_piglin" | "pillager" | "vindicator" | "evoker" | "ravager"
            | "silverfish" | "endermite" | "guardian" | "hoglin" | "zoglin" | "warden" | "breeze" | "bogged" => {
                Self::Hostile
            }
            "pig" | "cow" | "sheep" | "chicken" | "rabbit" | "horse" | "donkey" | "mule" | "llama" | "cat"
            | "wolf" | "fox" | "bee" | "goat" | "villager" | "turtle" | "parrot" | "mooshroom" | "panda"
            | "axolotl" | "frog" | "camel" | "sniffer" | "armadillo" | "strider" | "cod" | "salmon"
            | "squid" | "glow_squid" | "dolphin" | "bat" | "allay" | "ocelot" | "polar_bear" => Self::Passive,
            _ => Self::Other,
        }
    }

    const fn argb(self) -> u32 {
        match self {
            Self::Hostile => 0xFFFF_4040,
            Self::Passive => 0xFF40_E040,
            Self::Player => 0xFF40_A0FF,
            Self::Item => 0xFFFF_E040,
            Self::Other => 0xFFC0_C0C0,
        }
    }
}

#[derive(Default)]
struct State {
    enabled: bool,
    zoom: usize,
    dimension: String,
    pos: [f64; 3],
    yaw: f32,
    entities: Vec<(Kind, f64, f64)>,
    /// Entity kind ids already looked up, indexed by id.
    kinds: Vec<Option<Kind>>,
    waypoints: Vec<Waypoint>,
    remote: Vec<RemotePlayer>,
    stats: ServerStats,
    seq: u32,
    notice: String,
    net: bool,
    added: u32,
    /// A digest of everything drawn, so an unchanged radar returns `unchanged`.
    drawn: u64,
    dirty: bool,
}

impl State {
    /// The kind behind an entity kind id, asking the host only the first time an id is seen.
    fn kind(&mut self, id: u32) -> Kind {
        let index = id as usize;
        if let Some(Some(kind)) = self.kinds.get(index) {
            return *kind;
        }
        let kind = view::entity_kind_name(id).map_or(Kind::Other, |name| Kind::of(&name));
        if self.kinds.len() <= index {
            self.kinds.resize(index + 1, None);
        }
        self.kinds[index] = Some(kind);
        kind
    }
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State { enabled: true, zoom: 1, ..State::default() });
}

fn send(s: &mut State, m: &Up) {
    let (ch, bytes) = m.encode();
    if let Err(e) = net::send(ch, &bytes) {
        s.notice = format!("send {ch}: {e:?}");
        s.dirty = true;
    }
}

struct Radar;

impl Guest for Radar {
    fn init(init: InitInfo) -> Result<InitResult, String> {
        info(&format!("radar {} started, server route: {}", init.mod_version, init.session.net_negotiated));
        STATE.with_borrow_mut(|s| s.net = init.session.net_negotiated);
        Ok(InitResult { subscriptions: EventKinds::TICK | EventKinds::WORLD | EventKinds::INPUT })
    }

    fn handle_events(events: Vec<Event>) {
        STATE.with_borrow_mut(|s| {
            for event in events {
                match event {
                    Event::SessionStarted(_) if s.net => send(s, &Up::Sync),
                    Event::WorldChanged(w) => {
                        // A new world epoch: everything cached belongs to the old world.
                        s.dimension = w.dimension;
                        s.waypoints.clear();
                        s.remote.clear();
                        s.dirty = true;
                        if s.net {
                            send(s, &Up::Sync);
                        }
                    }
                    Event::Action(a) if a.pressed => match a.action.as_str() {
                        "toggle" => {
                            s.enabled = !s.enabled;
                            s.dirty = true;
                        }
                        "zoom" => {
                            s.zoom = (s.zoom + 1) % ZOOMS.len();
                            s.dirty = true;
                        }
                        "mark" if s.net => {
                            s.added += 1;
                            let m = Up::AddWaypoint { name: format!("WP{}", s.added), pos: s.pos };
                            send(s, &m);
                        }
                        "clear" if s.net => send(s, &Up::ClearWaypoints),
                        _ => {}
                    },
                    Event::NetMessage(m) => match Down::decode(&m.channel, &m.payload) {
                        Ok(Down::Waypoints { dimension, list }) if dimension == s.dimension => {
                            s.waypoints = list;
                            s.dirty = true;
                        }
                        Ok(Down::Players { dimension, seq, stats, list }) if dimension == s.dimension => {
                            s.remote = list;
                            s.stats = stats;
                            s.seq = seq;
                            s.dirty = true;
                        }
                        Ok(Down::Notice(n)) => {
                            s.notice = n;
                            s.dirty = true;
                        }
                        // A message for a world the player already left.
                        Ok(_) => {}
                        Err(_) => info("dropped a malformed radar message"),
                    },
                    _ => {}
                }
            }
            if !s.enabled {
                return;
            }
            if let Ok(p) = view::local_player() {
                s.pos = [p.pos.x, p.pos.y, p.pos.z];
                s.yaw = p.yaw;
                if s.dimension.is_empty() {
                    s.dimension = p.world.dimension;
                }
            }
            let radius = ZOOMS[s.zoom];
            s.entities.clear();
            if let Ok(list) = view::nearby_entities(radius, MAX_ENTITIES) {
                for e in list {
                    let kind = s.kind(e.kind);
                    s.entities.push((kind, e.pos.x - s.pos[0], e.pos.z - s.pos[2]));
                }
            }
        });
    }

    fn render(frame: FrameInfo) -> FrameOutput {
        STATE.with_borrow_mut(|s| {
            if !s.enabled {
                if s.drawn == 0 {
                    return FrameOutput::Unchanged;
                }
                s.drawn = 0;
                return FrameOutput::Clear;
            }
            let commands = draw(s, frame.gui_width as i32);
            let digest = digest(&commands);
            if digest == s.drawn && !s.dirty {
                return FrameOutput::Unchanged;
            }
            s.drawn = digest;
            s.dirty = false;
            FrameOutput::Commands(commands)
        })
    }

    fn shutdown() {}
}

/// Projects a world offset onto the radar so the player's heading points up.
fn project(dx: f64, dz: f64, yaw: f32, scale: f64) -> (f64, f64) {
    let (sin, cos) = (yaw as f64).to_radians().sin_cos();
    let forward = -dx * sin + dz * cos;
    let right = -dx * cos - dz * sin;
    (right * scale, -forward * scale)
}

fn draw(s: &State, gui_width: i32) -> Vec<DrawCommand> {
    let x0 = gui_width - SIZE - 6;
    let y0 = 6;
    let half = SIZE as f64 / 2.0;
    let radius = ZOOMS[s.zoom];
    let scale = half / radius;
    let cx = x0 as f64 + half;
    let cy = y0 as f64 + half;
    let mut out = Vec::with_capacity(8 + s.entities.len() + s.waypoints.len() + s.remote.len());
    let rect = |x: i32, y: i32, w: u32, h: u32, argb: u32| DrawCommand::FillRect(RectCmd { x, y, w, h, argb });
    let text = |x: i32, y: i32, t: String, argb: u32| DrawCommand::Text(TextCmd { x, y, text: t, argb, shadow: true });

    out.push(rect(x0 - 1, y0 - 1, SIZE as u32 + 2, SIZE as u32 + 2, 0xFF30_3030));
    out.push(rect(x0, y0, SIZE as u32, SIZE as u32, 0xB000_0000));
    for &(kind, dx, dz) in &s.entities {
        let (px, py) = project(dx, dz, s.yaw, scale);
        if px.abs() < half - 1.0 && py.abs() < half - 1.0 {
            out.push(rect((cx + px) as i32 - 1, (cy + py) as i32 - 1, 2, 2, kind.argb()));
        }
    }
    let mut marker = |pos: [f64; 3], argb: u32| {
        let (px, py) = project(pos[0] - s.pos[0], pos[2] - s.pos[2], s.yaw, scale);
        let clamp = half - 2.0;
        out.push(rect((cx + px.clamp(-clamp, clamp)) as i32 - 1, (cy + py.clamp(-clamp, clamp)) as i32 - 1, 3, 3, argb));
    };
    for w in &s.waypoints {
        marker(w.pos, 0xFFFF_AA00);
    }
    for p in &s.remote {
        marker(p.pos, Kind::Player.argb());
    }
    out.push(rect(cx as i32 - 1, cy as i32 - 2, 2, 4, 0xFFFF_FFFF));

    let mut y = y0 + SIZE + 3;
    out.push(text(x0, y, format!("{}m  {} ent  {} wp", radius as u32, s.entities.len(), s.waypoints.len()), 0xFFE0_E0E0));
    y += 10;

    // Nearest waypoints, distances right-aligned with hud.measure-text.
    let mut nearest: Vec<(f64, &Waypoint)> = s
        .waypoints
        .iter()
        .map(|w| (((w.pos[0] - s.pos[0]).powi(2) + (w.pos[2] - s.pos[2]).powi(2)).sqrt(), w))
        .collect();
    nearest.sort_by(|a, b| a.0.total_cmp(&b.0));
    nearest.truncate(LIST_ROWS);
    let distances: Vec<String> = nearest.iter().map(|(d, _)| format!("{d:.0}m")).collect();
    let widths = hud::measure_text(&distances).unwrap_or_else(|_| vec![0; distances.len()]);
    for (((_, w), d), width) in nearest.iter().zip(&distances).zip(&widths) {
        out.push(text(x0, y, w.name.clone(), 0xFFFF_AA00));
        out.push(text(x0 + SIZE - *width as i32, y, d.clone(), 0xFFFF_FFFF));
        y += 10;
    }
    if s.net {
        out.push(text(x0, y, format!("srv {:.1} mspt #{}", s.stats.mspt, s.seq), 0xFF90_90A0));
    } else {
        out.push(text(x0, y, "no server route".into(), 0xFF90_90A0));
    }
    if !s.notice.is_empty() {
        out.push(text(x0, y + 10, s.notice.clone(), 0xFFFF_8080));
    }
    out
}

/// FNV-1a over the drawn commands.
fn digest(commands: &[DrawCommand]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    let mut eat = |v: u64| {
        h ^= v;
        h = h.wrapping_mul(0x0100_0000_01b3);
    };
    for c in commands {
        match c {
            DrawCommand::FillRect(r) => {
                eat(1);
                eat(((r.x as u64) << 32) | r.y as u32 as u64);
                eat(((r.w as u64) << 32) | r.h as u64);
                eat(r.argb as u64);
            }
            DrawCommand::Text(t) => {
                eat(2);
                eat(((t.x as u64) << 32) | t.y as u32 as u64);
                t.text.bytes().for_each(|b| eat(b as u64));
            }
        }
    }
    h | 1
}

export!(Radar);
