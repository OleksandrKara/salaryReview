-- Russian translation of the title for SOPs and KB articles, alongside the existing body_ru/
-- change_note_ru columns (V?? already added those). Titles were never localized — staff whose
-- app language is Russian always saw the English title even when the body itself was already
-- translated. Nullable, same "falls back to English when unset" convention as body_ru.
ALTER TABLE sops ADD COLUMN title_ru TEXT;
ALTER TABLE kb_articles ADD COLUMN title_ru TEXT;
