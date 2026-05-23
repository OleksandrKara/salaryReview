-- Providers
INSERT INTO providers (name, display_name, commission_rate, card_tip_fee_rate, active) VALUES
    ('Anna Lastname', 'Anna', 0.4500, 0.0350, TRUE),
    ('Bea Lastname',  'Bea',  0.5000, 0.0350, TRUE);

-- May 2026, first half (1-15)
INSERT INTO pay_periods (year, month, half, label) VALUES
    (2026, 5, 'FIRST',  '1-15 May 2026'),
    (2026, 5, 'SECOND', '16-31 May 2026');

-- Anna's real-world entry for 1-15 May 2026 (the example used to derive the formula)
INSERT INTO period_entries
    (provider_id, pay_period_id, procedures, card_total, cash_total, card_tips, adjustments_amount, adjustments_note)
VALUES
    ((SELECT id FROM providers WHERE display_name = 'Anna'),
     (SELECT id FROM pay_periods WHERE year = 2026 AND month = 5 AND half = 'FIRST'),
     5, 473.00, 291.00, 74.30, 0.00, NULL);
