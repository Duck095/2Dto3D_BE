CREATE INDEX ix_design_projects_owner_updated_history
    ON design_projects(owner_user_id, updated_at DESC, project_id DESC);
