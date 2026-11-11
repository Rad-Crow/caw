-- a group link's token is shared with every member and replaced in place; the previous token stays valid until prev_until
ALTER TABLE links ADD COLUMN kind TEXT NOT NULL DEFAULT 'contact';
ALTER TABLE links ADD COLUMN gen INTEGER NOT NULL DEFAULT 0;
ALTER TABLE links ADD COLUMN prev_token_hash TEXT;
ALTER TABLE links ADD COLUMN prev_until INTEGER;
