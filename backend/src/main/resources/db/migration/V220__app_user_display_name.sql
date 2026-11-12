-- Name shown for owners and managers (lead owner labels, Telegram). Providers keep using providers.display_name.
ALTER TABLE app_user ADD COLUMN display_name VARCHAR(100);
UPDATE app_user SET display_name = 'Alex Kara' WHERE id = 1 AND role = 'OWNER' AND display_name IS NULL;
