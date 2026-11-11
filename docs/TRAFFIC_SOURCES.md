# Traffic source taxonomy

One definition: `landing/TrafficSource.java` (used by site conversions, SMS leads, calls, Academy, CRM lead source). Dashboard SQL buckets (`TrafficSourceSql`) are parity-tested against it.

Paid is claimed only from explicit markers: gclid/gbraid/wbraid (Google Ads), msclkid (Bing Ads), ttclid (TikTok Ads), or utm_medium in cpc, ppc, paid, paid_social, paidsocial, display, cpm. `fbclid` alone is never proof of paid (the Instagram app adds it to bio-link taps): it gives "Facebook/Instagram (click, unspecified)" unless the referrer or utm says more. Google tooling referrers (Tag Assistant, Analytics, Ads, Search Console) count as Direct.

Labels: Google Ads, Google (organic), Google Business Profile, Yelp Ads, Yelp (organic), Instagram Ads (Meta), Instagram (organic), Facebook Ads (Meta), Facebook (organic), Meta Ads, Bing Ads, Bing (organic), DuckDuckGo/Yahoo/Brave/Ecosia (organic), TikTok Ads, TikTok, YouTube, Pinterest, Nextdoor, Reddit, LinkedIn, X / Twitter, Apple Maps, ChatGPT/Perplexity/Gemini/Claude/Copilot (AI), Email (Mailchimp), SMS campaign, Linktree, Direct / No referrer, Referral: <host> (unknown hosts only).

## UTM conventions for links we place
- Yelp Ads: `?utm_source=yelp&utm_medium=paid&utm_campaign=<name>`
- Meta ads: `?utm_source=instagram|facebook&utm_medium=paid_social&utm_campaign=<name>`
- Google Ads: auto-tagging (gclid) plus `utm_source=google&utm_medium=cpc`
- Google Business Profile link: `?utm_source=google&utm_medium=organic&utm_campaign=gbp`
- Instagram bio: `?utm_source=ig&utm_medium=social`
- Mailchimp: `utm_source=mailchimp&utm_medium=email`

Limits: a Yelp or Meta ad click without utm_medium looks organic, so every paid link must carry the UTMs above. Dashboard bucket `meta_ads` stays broader (fbclid without utm_medium=social); CRM labels are strict.
