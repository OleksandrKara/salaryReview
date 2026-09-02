package com.salonreview.ai;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;

/**
 * Resolves which language an AI-powered feature should respond in, from the caller's own account
 * setting — re-read from the database on every call (not cached in the session/JWT) so a language
 * change takes effect on the caller's very next request. Extracted from three previously identical
 * private {@code language(AppUserPrincipal)} methods duplicated across
 * {@code FunnelAnalysisController}, {@code RagController}, and {@code SmsActivityController}
 * (seo-intelligence-advisor design.md D7/tasks.md 1.2) — a fourth AI feature (the SEO Advisor)
 * needing the identical logic was the point at which a shared utility stopped being optional.
 */
public final class LanguageResolver {

    private LanguageResolver() {}

    public static Language resolve(AppUserRepository users, AppUserPrincipal me) {
        if (me == null) return Language.EN;
        return users.findById(me.getUserId())
                .map(AppUser::getPreferredLanguage)
                .orElse(Language.EN) == Language.RU ? Language.RU : Language.EN;
    }
}
