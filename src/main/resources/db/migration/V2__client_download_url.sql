ALTER TABLE system_config
    ADD COLUMN IF NOT EXISTS client_download_url VARCHAR(500) NOT NULL DEFAULT '';
