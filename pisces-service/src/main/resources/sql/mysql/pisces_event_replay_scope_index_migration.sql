ALTER TABLE pisces_experiment_event
    ADD INDEX idx_event_replay_scope (experiment_id, group_id, event_time, event_type);

ALTER TABLE pisces_experiment_exposure
    ADD INDEX idx_exposure_replay_scope (experiment_id, group_id, exposed_at);
