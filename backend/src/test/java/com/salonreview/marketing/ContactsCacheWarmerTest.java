package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContactsCacheWarmerTest {

    @Test
    void warmsEveryConnectedBusinessAndKeepsGoingAfterAFailure() {
        SquareConnectionRepository connections = mock(SquareConnectionRepository.class);
        MarketingContactsService service = mock(MarketingContactsService.class);
        CurrentBusinessContext context = new CurrentBusinessContext();
        SquareConnection a = new SquareConnection();
        a.setBusinessId(1L);
        SquareConnection b = new SquareConnection();
        b.setBusinessId(2L);
        when(connections.findAll()).thenReturn(List.of(a, b));
        List<Long> warmed = new ArrayList<>();
        doAnswer(inv -> {
            Long id = context.id();
            warmed.add(id);
            if (id == 1L) throw new RuntimeException("Square down");
            return null;
        }).when(service).warmContactsCache();

        new ContactsCacheWarmer(connections, service, context).warm();

        assertThat(warmed).containsExactly(1L, 2L);
    }
}
