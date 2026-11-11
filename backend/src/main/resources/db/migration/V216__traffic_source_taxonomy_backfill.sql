-- Re-label rows whose source is a known host that the old classifier left as "Referral: <host>"
-- (see landing.TrafficSource). Only touches those labels; never Direct, Google organic or manual sources.
UPDATE lead_intake SET traffic_source = CASE
        WHEN traffic_source ~* '^Referral: ([a-z0-9-]+\.)*yelp\.' THEN 'Yelp (organic)'
        WHEN traffic_source ~* '^Referral: ([a-z0-9-]+\.)*duckduckgo\.com' THEN 'DuckDuckGo (organic)'
        WHEN traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(campaign-archive|list-manage)\.com' THEN 'Email (Mailchimp)'
        WHEN traffic_source ~* '^Referral: linktr\.ee' THEN 'Linktree'
        END
WHERE traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(yelp\.|duckduckgo\.com|campaign-archive\.com|list-manage\.com|linktr\.ee)';

UPDATE lead_intake SET source = traffic_source
WHERE NOT source_manual AND source = 'Other'
  AND traffic_source IN ('Yelp (organic)', 'DuckDuckGo (organic)', 'Email (Mailchimp)', 'Linktree');

UPDATE lead_intake SET traffic_source = 'ChatGPT (AI)', source = CASE WHEN source_manual THEN source ELSE 'ChatGPT (AI)' END
WHERE lower(traffic_source) = 'chatgpt.com';

UPDATE marketing.contacts SET marketing_traffic_source = CASE
        WHEN marketing_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*yelp\.' THEN 'Yelp (organic)'
        WHEN marketing_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*duckduckgo\.com' THEN 'DuckDuckGo (organic)'
        WHEN marketing_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(campaign-archive|list-manage)\.com' THEN 'Email (Mailchimp)'
        WHEN marketing_traffic_source ~* '^Referral: linktr\.ee' THEN 'Linktree'
        END
WHERE marketing_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(yelp\.|duckduckgo\.com|campaign-archive\.com|list-manage\.com|linktr\.ee)';

UPDATE marketing.contacts SET original_traffic_source = CASE
        WHEN original_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*yelp\.' THEN 'Yelp (organic)'
        WHEN original_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*duckduckgo\.com' THEN 'DuckDuckGo (organic)'
        WHEN original_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(campaign-archive|list-manage)\.com' THEN 'Email (Mailchimp)'
        WHEN original_traffic_source ~* '^Referral: linktr\.ee' THEN 'Linktree'
        END
WHERE original_traffic_source ~* '^Referral: ([a-z0-9-]+\.)*(yelp\.|duckduckgo\.com|campaign-archive\.com|list-manage\.com|linktr\.ee)';
