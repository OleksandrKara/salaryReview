package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class CheckoutReviewFlowRecoveryServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private final SmsReplyFlowRepository flows = mock(SmsReplyFlowRepository.class);
    private final SmsMessageRepository messages = mock(SmsMessageRepository.class);
    private final CheckoutReviewReplyService replyService = mock(CheckoutReviewReplyService.class);
    private final CheckoutReviewFlowRecoveryService service =
            new CheckoutReviewFlowRecoveryService(flows, messages, replyService);

    private static SmsReplyFlow flow(String state) {
        return SmsReplyFlow.builder().id(9L).businessId(BUSINESS_ID).phoneNumber("+15551234567").state(state).build();
    }

    @Test
    void retriesUsingTheRealInboundReplyText() {
        SmsReplyFlow flow = flow(SmsReplyFlow.STATE_AWAITING_REPLY);
        when(flows.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(flow));
        SmsMessage inbound = SmsMessage.builder().body("5!!").build();
        when(messages.findFirstByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(BUSINESS_ID, "+15551234567", "INBOUND"))
                .thenReturn(Optional.of(inbound));

        service.retry(BUSINESS_ID, 9L);

        verify(replyService).sendBranchReply(flow, true);
        verify(flows).save(flow);
        assertThat(flow.getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
    }

    @Test
    void negativeReplyTextRoutesToTheNegativeBranch() {
        SmsReplyFlow flow = flow(SmsReplyFlow.STATE_AWAITING_REPLY);
        when(flows.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(flow));
        when(messages.findFirstByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(BUSINESS_ID, "+15551234567", "INBOUND"))
                .thenReturn(Optional.of(SmsMessage.builder().body("2, not great").build()));

        service.retry(BUSINESS_ID, 9L);

        verify(replyService).sendBranchReply(flow, false);
    }

    @Test
    void rejectsAFlowThatIsNotAwaitingReply() {
        when(flows.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(flow(SmsReplyFlow.STATE_COMPLETED)));

        assertThatThrownBy(() -> service.retry(BUSINESS_ID, 9L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(replyService);
    }

    @Test
    void rejectsAMissingFlow() {
        when(flows.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(BUSINESS_ID, 9L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsWhenNoInboundMessageIsOnFile() {
        when(flows.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(flow(SmsReplyFlow.STATE_AWAITING_REPLY)));
        when(messages.findFirstByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(BUSINESS_ID, 9L)).isInstanceOf(ResponseStatusException.class);
        verify(replyService, never()).sendBranchReply(any(), anyBoolean());
    }
}
