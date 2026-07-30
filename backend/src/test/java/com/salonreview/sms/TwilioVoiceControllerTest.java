package com.salonreview.sms;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Static TwiML call-forwarding webhook — see openspec/changes/lead-followup-and-manager-inbox
 * tasks.md section 6.
 */
class TwilioVoiceControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TwilioVoiceController()).build();

    @Test
    void postReturnsDialTwiml() throws Exception {
        mvc.perform(post("/api/public/voice/inbound"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<Dial>+16193231185</Dial>")));
    }

    @Test
    void getReturnsDialTwimlToo() throws Exception {
        mvc.perform(get("/api/public/voice/inbound"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<Dial>+16193231185</Dial>")));
    }
}
