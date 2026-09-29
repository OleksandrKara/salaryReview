package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.ProviderScheduleChangeStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProviderScheduleChangeActivityControllerTest {
    @Test
    void activityUsesAuthenticatedBusinessAndIgnoresCallerSuppliedTenant() throws Exception {
        var context = mock(CurrentBusinessContext.class);
        var store = mock(ProviderScheduleChangeStore.class);
        when(context.id()).thenReturn(2L);
        when(store.history(2L)).thenReturn(new ProviderScheduleChangeStore.History(List.of(), List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new ProviderScheduleChangeActivityController(context, store)).build();
        mvc.perform(get("/api/owner/automations/activity/provider-schedule-changes").param("businessId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isEmpty()).andExpect(jsonPath("$.providers").isEmpty());
        verify(store).history(2L);
        verifyNoMoreInteractions(store);
    }
}
