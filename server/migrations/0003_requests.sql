-- a redeemed link is pending until its owner accepts it and open once mail arrives through it; until then it is deleted at expires_at
ALTER TABLE links ADD COLUMN state TEXT NOT NULL DEFAULT 'open';
ALTER TABLE links ADD COLUMN expires_at INTEGER;
ALTER TABLE links ADD COLUMN request TEXT;
