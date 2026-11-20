CREATE TABLE device_links (
    id            uuid         NOT NULL,
    user_id       integer      NOT NULL,
    device_public bytea        NOT NULL,
    linker_public bytea,
    sealed        bytea,
    created_at    timestamp(6) WITH TIME ZONE NOT NULL,
    expires_at    timestamp(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE device_links
    ADD CONSTRAINT fk_device_links_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
CREATE INDEX idx_device_links_user ON device_links (user_id);
CREATE INDEX idx_device_links_expires ON device_links (expires_at);
