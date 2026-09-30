//! Messages exchanged by the `example:radar` client and server components over `pumpkin:mux`.
//!
//! Pumpkin carries these bytes opaquely. Both components depend on this crate, so the codec is
//! written once. Integers and floats are little-endian; strings are a u16 length and UTF-8.

/// The channels declared in the manifest and in the server's `HELLO`, in wire order.
pub const CHANNELS: [&str; 7] = ["sync", "wp-add", "wp-clear", "bench", "waypoints", "players", "notice"];

#[derive(Debug, Clone, PartialEq)]
pub struct Waypoint {
    pub id: u32,
    pub owner: String,
    pub name: String,
    pub pos: [f64; 3],
}

#[derive(Debug, Clone, PartialEq)]
pub struct RemotePlayer {
    pub name: String,
    pub pos: [f64; 3],
}

/// Server timings carried with every broadcast, so client reports see both sides.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct ServerStats {
    /// The server's average milliseconds per tick.
    pub mspt: f32,
    /// Microseconds the plugin spent in its last payload handler.
    pub handler_micros: u32,
    /// Microseconds the plugin spent building and sending the last broadcast round.
    pub broadcast_micros: u32,
}

/// Benchmark controls. The server honors them only for operators.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Bench {
    /// Replace the benchmark entities with `count` stationary pigs around the sender.
    Entities(u32),
    /// Replace the generated waypoints in the sender's world with `count` waypoints.
    Waypoints(u32),
    /// Broadcast player positions every `period` ticks.
    BroadcastPeriod(u32),
}

/// Client to server.
#[derive(Debug, Clone, PartialEq)]
pub enum Up {
    Sync,
    AddWaypoint { name: String, pos: [f64; 3] },
    ClearWaypoints,
    Bench(Bench),
}

/// Server to client.
#[derive(Debug, Clone, PartialEq)]
pub enum Down {
    Waypoints { dimension: String, list: Vec<Waypoint> },
    Players { dimension: String, seq: u32, stats: ServerStats, list: Vec<RemotePlayer> },
    Notice(String),
}

#[derive(Debug, PartialEq, Eq)]
pub struct Malformed;

struct W(Vec<u8>);

impl W {
    fn u8(&mut self, v: u8) -> &mut Self {
        self.0.push(v);
        self
    }
    fn u16(&mut self, v: u16) -> &mut Self {
        self.0.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn u32(&mut self, v: u32) -> &mut Self {
        self.0.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn f32(&mut self, v: f32) -> &mut Self {
        self.0.extend_from_slice(&v.to_le_bytes());
        self
    }
    fn pos(&mut self, p: [f64; 3]) -> &mut Self {
        for c in p {
            self.0.extend_from_slice(&c.to_le_bytes());
        }
        self
    }
    fn str(&mut self, s: &str) -> &mut Self {
        let b = &s.as_bytes()[..s.len().min(u16::MAX as usize)];
        self.u16(b.len() as u16);
        self.0.extend_from_slice(b);
        self
    }
}

struct R<'a>(&'a [u8]);

impl R<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], Malformed> {
        if self.0.len() < n {
            return Err(Malformed);
        }
        let (a, b) = self.0.split_at(n);
        self.0 = b;
        Ok(a)
    }
    fn u8(&mut self) -> Result<u8, Malformed> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, Malformed> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().map_err(|_| Malformed)?))
    }
    fn u32(&mut self) -> Result<u32, Malformed> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().map_err(|_| Malformed)?))
    }
    fn f32(&mut self) -> Result<f32, Malformed> {
        Ok(f32::from_le_bytes(self.take(4)?.try_into().map_err(|_| Malformed)?))
    }
    fn f64(&mut self) -> Result<f64, Malformed> {
        Ok(f64::from_le_bytes(self.take(8)?.try_into().map_err(|_| Malformed)?))
    }
    fn pos(&mut self) -> Result<[f64; 3], Malformed> {
        Ok([self.f64()?, self.f64()?, self.f64()?])
    }
    fn str(&mut self) -> Result<String, Malformed> {
        let n = self.u16()? as usize;
        String::from_utf8(self.take(n)?.to_vec()).map_err(|_| Malformed)
    }
    fn end(&self) -> Result<(), Malformed> {
        if self.0.is_empty() { Ok(()) } else { Err(Malformed) }
    }
}

impl Up {
    /// The channel this message travels on, and its bytes.
    pub fn encode(&self) -> (&'static str, Vec<u8>) {
        let mut w = W(Vec::new());
        let ch = match self {
            Self::Sync => "sync",
            Self::AddWaypoint { name, pos } => {
                w.str(name).pos(*pos);
                "wp-add"
            }
            Self::ClearWaypoints => "wp-clear",
            Self::Bench(b) => {
                match *b {
                    Bench::Entities(n) => w.u8(1).u32(n),
                    Bench::Waypoints(n) => w.u8(2).u32(n),
                    Bench::BroadcastPeriod(n) => w.u8(3).u32(n),
                };
                "bench"
            }
        };
        (ch, w.0)
    }

    pub fn decode(channel: &str, bytes: &[u8]) -> Result<Self, Malformed> {
        let mut r = R(bytes);
        let m = match channel {
            "sync" => Self::Sync,
            "wp-add" => Self::AddWaypoint { name: r.str()?, pos: r.pos()? },
            "wp-clear" => Self::ClearWaypoints,
            "bench" => Self::Bench(match (r.u8()?, r.u32()?) {
                (1, n) => Bench::Entities(n),
                (2, n) => Bench::Waypoints(n),
                (3, n) => Bench::BroadcastPeriod(n),
                _ => return Err(Malformed),
            }),
            _ => return Err(Malformed),
        };
        r.end()?;
        Ok(m)
    }
}

impl Down {
    pub fn encode(&self) -> (&'static str, Vec<u8>) {
        let mut w = W(Vec::new());
        let ch = match self {
            Self::Waypoints { dimension, list } => {
                w.str(dimension).u16(list.len().min(u16::MAX as usize) as u16);
                for p in list.iter().take(u16::MAX as usize) {
                    w.u32(p.id).str(&p.owner).str(&p.name).pos(p.pos);
                }
                "waypoints"
            }
            Self::Players { dimension, seq, stats, list } => {
                w.str(dimension).u32(*seq).f32(stats.mspt).u32(stats.handler_micros).u32(stats.broadcast_micros);
                w.u16(list.len().min(u16::MAX as usize) as u16);
                for p in list.iter().take(u16::MAX as usize) {
                    w.str(&p.name).pos(p.pos);
                }
                "players"
            }
            Self::Notice(s) => {
                w.str(s);
                "notice"
            }
        };
        (ch, w.0)
    }

    pub fn decode(channel: &str, bytes: &[u8]) -> Result<Self, Malformed> {
        let mut r = R(bytes);
        let m = match channel {
            "waypoints" => {
                let dimension = r.str()?;
                let n = r.u16()?;
                let mut list = Vec::with_capacity(n as usize);
                for _ in 0..n {
                    list.push(Waypoint { id: r.u32()?, owner: r.str()?, name: r.str()?, pos: r.pos()? });
                }
                Self::Waypoints { dimension, list }
            }
            "players" => {
                let dimension = r.str()?;
                let seq = r.u32()?;
                let stats = ServerStats { mspt: r.f32()?, handler_micros: r.u32()?, broadcast_micros: r.u32()? };
                let n = r.u16()?;
                let mut list = Vec::with_capacity(n as usize);
                for _ in 0..n {
                    list.push(RemotePlayer { name: r.str()?, pos: r.pos()? });
                }
                Self::Players { dimension, seq, stats, list }
            }
            "notice" => Self::Notice(r.str()?),
            _ => return Err(Malformed),
        };
        r.end()?;
        Ok(m)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip() {
        let ups = [
            Up::Sync,
            Up::AddWaypoint { name: "Base".into(), pos: [1.5, 64.0, -3.25] },
            Up::ClearWaypoints,
            Up::Bench(Bench::Entities(128)),
            Up::Bench(Bench::Waypoints(200)),
            Up::Bench(Bench::BroadcastPeriod(1)),
        ];
        for m in ups {
            let (ch, b) = m.encode();
            assert_eq!(Up::decode(ch, &b), Ok(m));
        }
        let downs = [
            Down::Waypoints {
                dimension: "minecraft:overworld".into(),
                list: vec![Waypoint { id: 7, owner: "Alex".into(), name: "Home".into(), pos: [0.0, 70.0, 5.0] }],
            },
            Down::Players {
                dimension: "minecraft:the_nether".into(),
                seq: 9,
                stats: ServerStats { mspt: 1.5, handler_micros: 40, broadcast_micros: 120 },
                list: vec![RemotePlayer { name: "Steve".into(), pos: [100.0, 64.0, -20.0] }],
            },
            Down::Notice("hi".into()),
        ];
        for m in downs {
            let (ch, b) = m.encode();
            assert_eq!(Down::decode(ch, &b), Ok(m));
        }
    }

    #[test]
    fn channels_are_declared() {
        for ch in [Up::Sync.encode().0, Down::Notice(String::new()).encode().0] {
            assert!(CHANNELS.contains(&ch));
        }
    }

    #[test]
    fn truncated_and_trailing_bytes_are_rejected() {
        let (ch, mut b) = Up::AddWaypoint { name: "x".into(), pos: [0.0; 3] }.encode();
        assert_eq!(Up::decode(ch, &b[..b.len() - 1]), Err(Malformed));
        b.push(0);
        assert_eq!(Up::decode(ch, &b), Err(Malformed));
        assert_eq!(Up::decode("bench", &[9, 0, 0, 0, 0]), Err(Malformed));
    }
}
