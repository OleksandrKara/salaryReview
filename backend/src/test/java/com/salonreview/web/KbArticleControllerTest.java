package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.KbArticle;
import com.salonreview.domain.Role;
import com.salonreview.domain.SyncStatus;
import com.salonreview.kb.KbAiDraftService;
import com.salonreview.kb.KbArticleService;
import com.salonreview.kb.KbExportService;
import com.salonreview.kb.KbSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link KbArticleController}. Standalone MockMvc with an OWNER principal —
 * role-based 403s are enforced by {@code SecurityConfig} (GET=authenticated, writes/sync/ai-draft
 * =OWNER+MANAGER) and covered transitively, matching the triage/RAG controller-test convention.
 */
class KbArticleControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private KbArticleService articles;
    private KbSyncService sync;
    private KbAiDraftService aiDraft;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        articles = mock(KbArticleService.class);
        sync = mock(KbSyncService.class);
        aiDraft = mock(KbAiDraftService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        KbArticleController controller = new KbArticleController(articles, sync, aiDraft,
                mock(KbExportService.class), currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(Role.OWNER);
        when(me.getUsername()).thenReturn("owner");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private KbArticle sample() {
        return KbArticle.builder().id(1L).title("No-show policy").category("FAQ").body("$25 fee")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER, Role.PROVIDER)).contentHash("h")
                .syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
    }

    @Test
    @DisplayName("list returns 200 with mapped articles")
    void listSucceeds() throws Exception {
        when(articles.list(Role.OWNER, BUSINESS_ID)).thenReturn(List.of(sample()));
        mvc.perform(get("/api/kb-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("No-show policy"))
                .andExpect(jsonPath("$[0].syncStatus").value("SYNCED"))
                .andExpect(jsonPath("$[0].visibleRoles[2]").value("PROVIDER"));
    }

    @Test
    @DisplayName("missing article → 404")
    void getMissing() throws Exception {
        when(articles.get(any(), any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/kb-articles/9")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("blank title → 400")
    void createBlankTitle() throws Exception {
        mvc.perform(post("/api/kb-articles").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("title", "  ", "category", "FAQ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("concurrent sync-all → 409")
    void syncAllConflict() throws Exception {
        when(sync.syncAll(anyString(), any())).thenThrow(new KbSyncService.SyncInProgressException());
        mvc.perform(post("/api/kb-articles/sync-all")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ai-draft returns markdown")
    void aiDraftSucceeds() throws Exception {
        when(aiDraft.draft(anyString(), any())).thenReturn("# Title\n\nBody");
        mvc.perform(post("/api/kb-articles/ai-draft").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("prompt", "write a cancellation script"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("# Title\n\nBody"));
    }

    @Test
    @DisplayName("ai-draft when AI is disabled → 503")
    void aiDraftUnavailable() throws Exception {
        when(aiDraft.draft(anyString(), any())).thenThrow(new IllegalStateException("AI off"));
        mvc.perform(post("/api/kb-articles/ai-draft").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("prompt", "x"))))
                .andExpect(status().isServiceUnavailable());
    }
}
