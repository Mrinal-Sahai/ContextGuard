
CREATE INDEX idx_review_platform_external ON reviews(platform, owner, repository, external_id);
CREATE INDEX idx_review_status ON reviews(status);
CREATE INDEX idx_review_last_activity ON reviews(last_activity_at);
CREATE INDEX idx_snapshot_review_id ON snapshots(review_id);
CREATE INDEX idx_snapshot_created_at ON snapshots(created_at);
CREATE INDEX idx_notification_review_id ON notifications(review_id);
