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

    private final SmsReplyFlowRepository flows = mock(SmsReplyFlowRepository.class);
    private final SmsMessageRepository messages = mock(SmsMessageRepository.class);
    private final CheckoutReviewReplyService replyService = mock(CheckoutReviewReplyService.class);
    private final CheckoutReviewFlowRecoveryService service =
            new CheckoutReviewFlowRecoveryService(flows, messages, replyService);

    private static SmsReplyFlow flow(String state) {
        return SmsReplyFlow.builder().id(9L).phoneNumber("+15551234567").state(state).build();
    }

    @Test
    void retriesUsingTheRealInboundReplyText() {
        SmsReplyFlow flow = flow(SmsReplyFlow.STATE_AWAITING_REPLY);
        when(flows.findById(9L)).thenReturn(Optional.of(flow));
        SmsMessage inbound = SmsMessage.builder().body("5!!").build();
        when(messages.findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc("+15551234567", "INBOUND"))
                .thenReturn(Optional.of(inbound));

        service.retry(9L);

        verify(replyService).sendBranchReply(flow, true);
        verify(flows).save(flow);
        assertThat(flow.getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
    }

    @Test
    void negativeReplyTextRoutesToTheNegativeBranch() {
        SmsReplyFlow flow = flow(SmsReplyFlow.STATE_AWAITING_REPLY);
        when(flows.findById(9L)).thenReturn(Optional.of(flow));
        when(messages.findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc("+15551234567", "INBOUND"))
                .thenReturn(Optional.of(SmsMessage.builder().body("2, not great").build()));

        service.retry(9L);

        verify(replyService).sendBranchReply(flow, false);
    }

    @Test
    void rejectsAFlowThatIsNotAwaitingReply() {
        when(flows.findById(9L)).thenReturn(Optional.of(flow(SmsReplyFlow.STATE_COMPLETED)));

        assertThatThrownBy(() -> service.retry(9L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(replyService);
    }

    @Test
    void rejectsAMissingFlow() {
        when(flows.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(9L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsWhenNoInboundMessageIsOnFile() {
        when(flows.findById(9L)).thenReturn(Optional.of(flow(SmsReplyFlow.STATE_AWAITING_REPLY)));
        when(messages.findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(9L)).isInstanceOf(ResponseStatusException.class);
        verify(replyService, never()).sendBranchReply(any(), anyBoolean());
    }
}
