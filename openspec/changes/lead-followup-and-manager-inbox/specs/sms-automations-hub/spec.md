## MODIFIED Requirements

### Requirement: MANAGER role has read/reply access to conversations, but not automation control
The system SHALL allow MANAGER-role sessions to read the activity log, list/open conversations, mark
messages read, and send manual replies — the same access described in the `sms-manager-access`
capability. The system SHALL continue to restrict `PUT /api/owner/automations/{key}` (the
enable/disable toggle) and the automations-list registry endpoint's write path to OWNER only.

This supersedes the previous version of this requirement (from `sms-automations-hub`), which
restricted every `/api/owner/automations/**` endpoint — including the activity log — to OWNER only.
That blanket restriction is now narrowed: only the on/off toggle stays OWNER-exclusive.

#### Scenario: Manager can read the activity log
- **WHEN** a MANAGER-role session calls `GET /api/owner/automations/activity` or the unread-count
  endpoint
- **THEN** the response is `200`, not `403`

#### Scenario: Manager still cannot toggle an automation
- **WHEN** a MANAGER-role session calls `PUT /api/owner/automations/{key}`
- **THEN** the response is `403 Forbidden` and the automation's enabled state is unchanged
