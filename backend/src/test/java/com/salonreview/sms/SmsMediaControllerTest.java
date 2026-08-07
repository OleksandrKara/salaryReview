package com.salonreview.sms;

import com.salonreview.domain.SmsMessageMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public, unauthenticated media-serving endpoint — see V69/SmsMediaService. */
class SmsMediaControllerTest {

    private SmsMediaService mediaService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mediaService = mock(SmsMediaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SmsMediaController(mediaService)).build();
    }

    @Test
    @DisplayName("GET /api/public/sms-media/{token} serves the stored bytes with the stored content type")
    void servesStoredBytes() throws Exception {
        SmsMessageMedia media = SmsMessageMedia.builder().id(1L).smsMessageId(9L)
                .contentType("image/jpeg").fileData(new byte[]{1, 2, 3}).accessToken("abc12").build();
        when(mediaService.get("abc12")).thenReturn(Optional.of(media));

        mvc.perform(get("/api/public/sms-media/abc12"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("GET /api/public/sms-media/{token} 404s for an unknown token")
    void unknownTokenIs404() throws Exception {
        when(mediaService.get("nope1")).thenReturn(Optional.empty());

        mvc.perform(get("/api/public/sms-media/nope1"))
                .andExpect(status().isNotFound());
    }
}
