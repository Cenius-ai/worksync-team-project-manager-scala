# Usage walkthrough

Sign in with the demo **Admin** (`cenius@cenius.ai` / `cenius`) or **Member** (`member@worksync.dev` / `member123`) — the credentials are shown on the login card.

## Dashboard (`/app`)
Four stat cards (active projects, open tasks, tasks assigned to you, minutes logged in the last 7 days), an SVG status-distribution chart, and the six most recently updated tasks. Quick actions create a team/project/task. Members only ever see aggregates from teams they belong to; the empty state appears for brand-new accounts.

## Teams (`/teams`, `/teams/{id}`)
- Filter by name; create a team (you become owner + first member).
- On the team page: edit name/description, archive/restore, delete (only when the team has no projects and no other members), add teammates by email, remove members (owners can't be removed).
- Admins see every team; Members see only the teams they belong to (anything else is a styled 403).

## Projects (`/projects`, `/projects/{id}`)
- Search + team filter + All/Active/Archived tabs; create a project inside any active team you belong to (names must be unique per team).
- The project page shows a progress summary, team members, and the task **board** grouped into To-do / In progress / Done columns with due dates, priorities and assignees. New-task form, edit, archive/restore and delete (empty projects only) are on the page.

## Tasks (`/tasks`, `/tasks/new`, `/tasks/{id}`)
- The top-bar search runs a global search of titles/descriptions with filters for status, priority, project, team and assignee. Zero matches gets a reset-filters empty state.
- Task detail: metadata, a one-click status stepper, full edit form, delete with confirmation, the **comment feed** (with per-field validation) and **time logging** (minutes 1–1440, note, date) with per-task totals.

## Admin (`/admin/users`)
Admins can change any account between Admin/Member and activate/deactivate it. Your own account is protected, and Worksync always keeps at least one active admin. Members hitting this area see a styled 403.

## Keyboard & accessibility
Every form labels its fields, error text is announced with `role="alert"`, destructive actions confirm first, and focus rings are visible. The UI is responsive down to small phone widths.
