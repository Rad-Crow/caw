-- account ids identify accounts to contacts, so they are never reused
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    salt TEXT NOT NULL,
    is_admin INTEGER NOT NULL DEFAULT 0,
    disabled INTEGER NOT NULL DEFAULT 0,
    must_change INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE invites (
    id INTEGER PRIMARY KEY,
    token TEXT NOT NULL UNIQUE,
    note TEXT NOT NULL DEFAULT '',
    is_admin INTEGER NOT NULL DEFAULT 0,
    max_uses INTEGER,
    uses INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label TEXT NOT NULL DEFAULT '',
    sign_pub TEXT NOT NULL,
    enc_pub TEXT,
    enc_created_at INTEGER,
    enc_sig TEXT,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);

-- recent encryption keys per device, so devices can check stanzas sealed before a rotation
CREATE TABLE device_keys (
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    enc_pub TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (device_id, enc_pub)
);

-- device_id has no foreign key: removing a device deletes its sessions explicitly to close their connections
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT,
    user_agent TEXT,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE INDEX sessions_device ON sessions(device_id);

CREATE TABLE push_endpoints (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX push_endpoints_user ON push_endpoints(user_id);
CREATE INDEX push_endpoints_session ON push_endpoints(session);

CREATE TABLE pairings (
    ticket_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);

CREATE TABLE links (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    peer_server TEXT NOT NULL,
    peer_account TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_used INTEGER NOT NULL
);
CREATE INDEX links_user ON links(user_id);

CREATE TABLE mail (
    link_id TEXT NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    id TEXT NOT NULL,
    from_device TEXT NOT NULL,
    ttl INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    body TEXT NOT NULL,
    keys TEXT NOT NULL,
    sig TEXT NOT NULL,
    PRIMARY KEY (link_id, id)
);
CREATE INDEX mail_expiry ON mail(expires_at);

CREATE TABLE blobs (
    id TEXT PRIMARY KEY,
    link_id TEXT NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    mail_id TEXT,
    size INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX blobs_expiry ON blobs(expires_at);
CREATE INDEX blobs_link ON blobs(link_id);

-- blob and wrapped_key are encrypted by the client and again with the server secret
CREATE TABLE vaults (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 0,
    blob TEXT,
    wrapped_key TEXT,
    updated_at INTEGER NOT NULL
);
