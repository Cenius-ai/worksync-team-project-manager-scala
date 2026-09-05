-- ============================================================
-- Worksync schema (V1)
-- Dialect-safe for both H2 (PostgreSQL mode) and PostgreSQL:
--   * identity PKs, BIGINT epoch-millis timestamps,
--   * ISO-8601 (yyyy-MM-dd) dates as VARCHAR(10),
--   * enums expressed as CHECK constraints.
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(120) NOT NULL,
  role          VARCHAR(20)  NOT NULL,
  active        BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    BIGINT       NOT NULL,
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_role CHECK (role IN ('Admin', 'Member'))
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);
CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);

CREATE TABLE IF NOT EXISTS sessions (
  token      VARCHAR(36) PRIMARY KEY,
  user_id    BIGINT  NOT NULL,
  created_at BIGINT  NOT NULL,
  expires_at BIGINT  NOT NULL,
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions (expires_at);

CREATE TABLE IF NOT EXISTS teams (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(160) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  owner_id    BIGINT NOT NULL,
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  BIGINT NOT NULL,
  CONSTRAINT fk_teams_owner FOREIGN KEY (owner_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_teams_owner ON teams (owner_id);

CREATE TABLE IF NOT EXISTS team_members (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  team_id   BIGINT NOT NULL,
  user_id   BIGINT NOT NULL,
  joined_at BIGINT NOT NULL,
  CONSTRAINT uq_team_members UNIQUE (team_id, user_id),
  CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
  CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_team_members_team ON team_members (team_id);
CREATE INDEX IF NOT EXISTS idx_team_members_user ON team_members (user_id);

CREATE TABLE IF NOT EXISTS projects (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  team_id     BIGINT  NOT NULL,
  name        VARCHAR(160) NOT NULL,
  description VARCHAR(4000) NOT NULL DEFAULT '',
  owner_id    BIGINT  NOT NULL,
  status      VARCHAR(20) NOT NULL DEFAULT 'Active',
  created_at  BIGINT  NOT NULL,
  CONSTRAINT uq_projects_team_name UNIQUE (team_id, name),
  CONSTRAINT fk_projects_team FOREIGN KEY (team_id) REFERENCES teams (id),
  CONSTRAINT fk_projects_owner FOREIGN KEY (owner_id) REFERENCES users (id),
  CONSTRAINT ck_projects_status CHECK (status IN ('Active', 'Archived'))
);

CREATE INDEX IF NOT EXISTS idx_projects_team ON projects (team_id);
CREATE INDEX IF NOT EXISTS idx_projects_status ON projects (status);
CREATE INDEX IF NOT EXISTS idx_projects_owner ON projects (owner_id);

CREATE TABLE IF NOT EXISTS tasks (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  title       VARCHAR(200) NOT NULL,
  description VARCHAR(8000) NOT NULL DEFAULT '',
  status      VARCHAR(20) NOT NULL DEFAULT 'Todo',
  priority    VARCHAR(10) NOT NULL DEFAULT 'Medium',
  assignee_id BIGINT,
  reporter_id BIGINT NOT NULL,
  due_date    VARCHAR(10),
  created_at  BIGINT NOT NULL,
  updated_at  BIGINT NOT NULL,
  CONSTRAINT fk_tasks_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
  CONSTRAINT fk_tasks_assignee FOREIGN KEY (assignee_id) REFERENCES users (id),
  CONSTRAINT fk_tasks_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
  CONSTRAINT ck_tasks_status CHECK (status IN ('Todo', 'InProgress', 'Done')),
  CONSTRAINT ck_tasks_priority CHECK (priority IN ('Low', 'Medium', 'High'))
);

CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks (project_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks (status);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks (assignee_id);
CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks (due_date);

CREATE TABLE IF NOT EXISTS comments (
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id   BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  body      VARCHAR(2000) NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT fk_comments_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
  CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id)
);

CREATE INDEX IF NOT EXISTS idx_comments_task ON comments (task_id);

CREATE TABLE IF NOT EXISTS time_entries (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id    BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  minutes    INT NOT NULL,
  note       VARCHAR(500),
  entry_date VARCHAR(10) NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT fk_time_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
  CONSTRAINT fk_time_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT ck_time_minutes CHECK (minutes BETWEEN 1 AND 1440)
);

CREATE INDEX IF NOT EXISTS idx_time_task ON time_entries (task_id);
CREATE INDEX IF NOT EXISTS idx_time_user ON time_entries (user_id);
CREATE INDEX IF NOT EXISTS idx_time_date ON time_entries (entry_date);
