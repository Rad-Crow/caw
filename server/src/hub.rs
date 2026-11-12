use std::{
    collections::{HashMap, HashSet},
    sync::Mutex,
};

use serde::Serialize;
use serde_json::{json, Value};
use tokio::sync::mpsc::UnboundedSender;

#[derive(Clone, Debug, Serialize, sqlx::FromRow)]
pub struct Mail {
    pub link_id: String,
    pub id: String,
    pub from_device: String,
    pub ttl: i64,
    pub created_at: i64,
    pub expires_at: i64,
    pub body: String,
    #[sqlx(json)]
    pub keys: Value,
    pub sig: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct Incoming {
    pub call_id: String,
    pub link_id: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Out {
    Hello {
        peer_id: String,
        incoming: Vec<Incoming>,
        calls: Vec<String>,
    },
    Incoming(Incoming),
    Ringing {
        call_id: String,
    },
    Joined {
        call_id: String,
        peers: Vec<String>,
    },
    PeerJoined {
        call_id: String,
        peer_id: String,
    },
    PeerLeft {
        call_id: String,
        peer_id: String,
    },
    Declined {
        call_id: String,
    },
    Ended {
        call_id: String,
        reason: String,
    },
    Signal {
        call_id: String,
        from: String,
        data: Value,
    },
    Mail(Mail),
    LinkRemoved {
        link_id: String,
    },
    // removed by one of the account's own devices; the others drop it silently
    LinkRevoked {
        link_id: String,
    },
    Vault {
        version: i64,
    },
    Devices,
    Requests,
    Sent {
        contact: String,
    },
    Error {
        message: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        call_id: Option<String>,
    },
    Pong,
}

#[derive(Debug)]
pub enum Effect {
    Push { user_id: i64, payload: Value },
    RingTimer { call_id: String, secs: u64 },
    Grace { peer_id: String, gen: u64 },
}

// how long a dropped connection keeps its seat in a call
pub const GRACE_SECS: u64 = 30;

// an owner is a signed-in device of an account here; a guest is a contact holding a link to that account
#[derive(Clone, Debug, PartialEq)]
pub enum Who {
    Owner { user: i64 },
    Guest { link: String, user: i64 },
}

// tx is None while a connection that was in a call is away and may come back
struct Conn {
    who: Who,
    session: Option<String>,
    tx: Option<UnboundedSender<Out>>,
    gen: u64,
}

// one-to-one: the caller is a guest on the callee's server; every callee device rings and the first answer wins
struct Call {
    link: String,
    user: i64,
    joined: HashSet<String>,
    answered: bool,
}

#[derive(Default)]
struct State {
    conns: HashMap<String, Conn>,
    calls: HashMap<String, Call>,
}

impl State {
    fn send_peer(&self, peer_id: &str, msg: Out) {
        if let Some(tx) = self.conns.get(peer_id).and_then(|c| c.tx.as_ref()) {
            let _ = tx.send(msg);
        }
    }

    fn user_peers(&self, user: i64) -> Vec<String> {
        self.conns
            .iter()
            .filter(|(_, c)| c.who == Who::Owner { user })
            .map(|(p, _)| p.clone())
            .collect()
    }

    fn send_user(&self, user: i64, msg: &Out) {
        for p in self.user_peers(user) {
            self.send_peer(&p, msg.clone());
        }
    }

    fn in_call(&self, peer_id: &str) -> bool {
        self.calls.values().any(|c| c.joined.contains(peer_id))
    }

    fn calls_of(&self, peer_id: &str) -> Vec<String> {
        self.calls.iter().filter(|(_, c)| c.joined.contains(peer_id)).map(|(id, _)| id.clone()).collect()
    }

    fn may_answer(&self, peer_id: &str, call: &Call) -> bool {
        self.conns.get(peer_id).is_some_and(|c| c.who == Who::Owner { user: call.user })
    }
}

pub struct Hub {
    state: Mutex<State>,
    fx: UnboundedSender<Effect>,
}

fn push_call(call_id: &str) -> Value {
    json!({ "type": "call", "call_id": call_id })
}

fn push_cancel(call_id: &str) -> Value {
    json!({ "type": "cancel", "call_id": call_id })
}

impl Hub {
    pub fn new(fx: UnboundedSender<Effect>) -> Self {
        Self {
            state: Mutex::default(),
            fx,
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn emit(&self, e: Effect) {
        let _ = self.fx.send(e);
    }

    // whether a peer id is free for this identity, or already its own (a reconnect)
    pub fn may_use(&self, peer_id: &str, who: &Who) -> bool {
        self.lock().conns.get(peer_id).is_none_or(|c| match (&c.who, who) {
            (Who::Owner { user: a }, Who::Owner { user: b }) => a == b,
            (Who::Guest { link: a, .. }, Who::Guest { link: b, .. }) => a == b,
            _ => false,
        })
    }

    // a peer id held by the same identity is a reconnect and takes over its seat, calls included; returns the generation for disconnect
    pub fn connect(&self, peer_id: &str, who: Who, session: Option<String>, tx: UnboundedSender<Out>) -> u64 {
        let mut s = self.lock();
        let gen = s.conns.get(peer_id).map_or(0, |c| c.gen + 1);
        let incoming = match &who {
            Who::Owner { user } => s
                .calls
                .iter()
                .filter(|(_, c)| !c.answered && c.user == *user)
                .map(|(id, c)| Incoming {
                    call_id: id.clone(),
                    link_id: c.link.clone(),
                })
                .collect(),
            Who::Guest { .. } => vec![],
        };
        s.conns.insert(peer_id.to_string(), Conn { who, session, tx: Some(tx), gen });
        let calls = s.calls_of(peer_id);
        s.send_peer(
            peer_id,
            Out::Hello {
                peer_id: peer_id.to_string(),
                incoming,
                calls,
            },
        );
        gen
    }

    // during a call the seat is held for GRACE_SECS so a brief network drop doesn't end it
    pub fn disconnect(&self, peer_id: &str, gen: u64) {
        let mut s = self.lock();
        if s.conns.get(peer_id).is_none_or(|c| c.gen != gen) {
            return;
        }
        if s.in_call(peer_id) {
            if let Some(c) = s.conns.get_mut(peer_id) {
                c.tx = None;
            }
            drop(s);
            self.emit(Effect::Grace {
                peer_id: peer_id.to_string(),
                gen,
            });
            return;
        }
        self.remove(&mut s, peer_id);
    }

    pub fn grace_over(&self, peer_id: &str, gen: u64) {
        let mut s = self.lock();
        if s.conns.get(peer_id).is_some_and(|c| c.gen == gen && c.tx.is_none()) {
            self.remove(&mut s, peer_id);
        }
    }

    fn remove(&self, s: &mut State, peer_id: &str) {
        for id in s.calls_of(peer_id) {
            self.leave(s, &id, peer_id);
        }
        s.conns.remove(peer_id);
    }

    pub fn kick_user(&self, user_id: i64) {
        let mut s = self.lock();
        let peers: Vec<String> = s
            .conns
            .iter()
            .filter(|(_, c)| matches!(&c.who, Who::Owner { user } | Who::Guest { user, .. } if *user == user_id))
            .map(|(p, _)| p.clone())
            .collect();
        for p in peers {
            self.remove(&mut s, &p);
        }
    }

    // closes a removed device's connections immediately rather than at the next session check
    pub fn kick_sessions(&self, sessions: &[String]) {
        let mut s = self.lock();
        let peers: Vec<String> = s
            .conns
            .iter()
            .filter(|(_, c)| c.session.as_ref().is_some_and(|t| sessions.contains(t)))
            .map(|(p, _)| p.clone())
            .collect();
        for p in peers {
            self.remove(&mut s, &p);
        }
    }

    // a revoked link can't keep a guest connection open
    pub fn drop_link(&self, link_id: &str) {
        let mut s = self.lock();
        let peers: Vec<String> = s
            .conns
            .iter()
            .filter(|(_, c)| matches!(&c.who, Who::Guest { link, .. } if link == link_id))
            .map(|(p, _)| p.clone())
            .collect();
        for p in peers {
            self.remove(&mut s, &p);
        }
    }

    // admin view only
    pub fn online(&self) -> HashSet<i64> {
        self.lock()
            .conns
            .values()
            .filter(|c| c.tx.is_some())
            .filter_map(|c| match &c.who {
                Who::Owner { user } => Some(*user),
                Who::Guest { .. } => None,
            })
            .collect()
    }

    pub fn send_user(&self, user: i64, msg: &Out) {
        self.lock().send_user(user, msg);
    }

    pub fn send_peer(&self, peer_id: &str, msg: Out) {
        self.lock().send_peer(peer_id, msg);
    }

    pub fn start_call(&self, peer_id: &str, call_id: &str, ring_secs: u64) -> Result<(), &'static str> {
        let mut s = self.lock();
        let Some(Who::Guest { link, user }) = s.conns.get(peer_id).map(|c| c.who.clone()) else {
            return Err("Calls are started through a contact link.");
        };
        if s.calls.contains_key(call_id) {
            return Err("Duplicate call ID.");
        }
        if s.in_call(peer_id) {
            return Err("Already in a call.");
        }
        s.calls.insert(
            call_id.to_string(),
            Call {
                link: link.clone(),
                user,
                joined: HashSet::from([peer_id.to_string()]),
                answered: false,
            },
        );
        s.send_user(
            user,
            &Out::Incoming(Incoming {
                call_id: call_id.to_string(),
                link_id: link,
            }),
        );
        s.send_peer(
            peer_id,
            Out::Ringing {
                call_id: call_id.to_string(),
            },
        );
        drop(s);
        self.emit(Effect::Push {
            user_id: user,
            payload: push_call(call_id),
        });
        self.emit(Effect::RingTimer {
            call_id: call_id.to_string(),
            secs: ring_secs,
        });
        Ok(())
    }

    pub fn accept(&self, peer_id: &str, call_id: &str) -> Result<(), &'static str> {
        let mut s = self.lock();
        if s.in_call(peer_id) {
            return Err("Already in a call.");
        }
        let call = s.calls.get(call_id).ok_or("The call has ended.")?;
        if call.answered || !s.may_answer(peer_id, call) {
            return Err("Not a participant in this call.");
        }
        let user = call.user;
        let call = s.calls.get_mut(call_id).ok_or("The call has ended.")?;
        call.answered = true;
        let peers: Vec<String> = call.joined.iter().cloned().collect();
        call.joined.insert(peer_id.to_string());
        s.send_peer(
            peer_id,
            Out::Joined {
                call_id: call_id.to_string(),
                peers: peers.clone(),
            },
        );
        for p in &peers {
            s.send_peer(
                p,
                Out::PeerJoined {
                    call_id: call_id.to_string(),
                    peer_id: peer_id.to_string(),
                },
            );
        }
        let elsewhere = Out::Ended {
            call_id: call_id.to_string(),
            reason: "answered_elsewhere".into(),
        };
        for p in s.user_peers(user) {
            if p != peer_id {
                s.send_peer(&p, elsewhere.clone());
            }
        }
        drop(s);
        self.emit(Effect::Push {
            user_id: user,
            payload: push_cancel(call_id),
        });
        Ok(())
    }

    pub fn reject(&self, peer_id: &str, call_id: &str) {
        let mut s = self.lock();
        let Some(call) = s.calls.get(call_id) else { return };
        if call.answered || !s.may_answer(peer_id, call) {
            return;
        }
        let callers: Vec<String> = call.joined.iter().cloned().collect();
        for p in &callers {
            s.send_peer(
                p,
                Out::Declined {
                    call_id: call_id.to_string(),
                },
            );
        }
        self.end(&mut s, call_id, "declined");
    }

    pub fn hangup(&self, peer_id: &str, call_id: &str) {
        let mut s = self.lock();
        let Some(call) = s.calls.get(call_id) else { return };
        if call.joined.contains(peer_id) {
            self.leave(&mut s, call_id, peer_id);
        } else if !call.answered && s.may_answer(peer_id, call) {
            drop(s);
            self.reject(peer_id, call_id);
        }
    }

    pub fn signal(&self, peer_id: &str, call_id: &str, to: &str, data: Value) -> Result<(), &'static str> {
        let s = self.lock();
        let call = s.calls.get(call_id).ok_or("The call has ended.")?;
        if !call.joined.contains(peer_id) || !call.joined.contains(to) {
            return Err("Peer is not in this call.");
        }
        s.send_peer(
            to,
            Out::Signal {
                call_id: call_id.to_string(),
                from: peer_id.to_string(),
                data,
            },
        );
        Ok(())
    }

    pub fn ring_timeout(&self, call_id: &str) {
        let mut s = self.lock();
        if s.calls.get(call_id).is_some_and(|c| !c.answered) {
            self.end(&mut s, call_id, "no_answer");
        }
    }

    fn leave(&self, s: &mut State, call_id: &str, peer_id: &str) {
        let Some(call) = s.calls.get_mut(call_id) else { return };
        if !call.joined.remove(peer_id) {
            return;
        }
        let answered = call.answered;
        let remaining: Vec<String> = call.joined.iter().cloned().collect();
        s.send_peer(
            peer_id,
            Out::Ended {
                call_id: call_id.to_string(),
                reason: "left".into(),
            },
        );
        for p in &remaining {
            s.send_peer(
                p,
                Out::PeerLeft {
                    call_id: call_id.to_string(),
                    peer_id: peer_id.to_string(),
                },
            );
        }
        self.end(s, call_id, if answered { "completed" } else { "cancelled" });
    }

    fn end(&self, s: &mut State, call_id: &str, reason: &str) {
        let Some(call) = s.calls.remove(call_id) else { return };
        let msg = Out::Ended {
            call_id: call_id.to_string(),
            reason: reason.to_string(),
        };
        for p in &call.joined {
            s.send_peer(p, msg.clone());
        }
        if !call.answered {
            s.send_user(call.user, &msg);
            self.emit(Effect::Push {
                user_id: call.user,
                payload: push_cancel(call_id),
            });
        }
    }
}
