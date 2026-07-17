SET search_path TO identity;

ALTER TABLE auth_sessions DROP COLUMN csrf_token_hash;
