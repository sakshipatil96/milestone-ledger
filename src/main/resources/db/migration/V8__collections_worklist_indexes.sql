-- Day 4 worklists paginate by the same scoped, stable order used by the API.
CREATE INDEX demand_project_created_time_idx ON demand (project_id, created_at, id);
CREATE INDEX receipt_project_recorded_time_idx ON receipt (project_id, recorded_at, id);
