package com.salonreview.timecards;

import java.util.List;

/** JSON shapes for the timecard API. Money fields are null when the caller must not see dollars. */
public final class TimecardDtos {

    private TimecardDtos() {}

    public record BreakDto(String start, String end, String kind) {}

    public record CardDto(String start, String end, List<BreakDto> breaks) {}

    public record ApptDto(String start, String end, String status) {}

    public record BreakQuestionDto(String kind, int index, String assumedStart, String assumedEnd) {}

    public record AnswerDto(String kind, int index, String answer, String start, String end, String reason) {}

    public record FindingDto(String code, String severity, long minutes, String message) {}

    public record DayView(
            String date,
            Long providerId,
            String providerName,
            String source,
            String status,
            boolean changedInSquare,
            List<CardDto> timecards,
            List<ApptDto> appointments,
            long paidMinutes,
            long regularMinutes,
            long overtimeMinutes,
            long doubleTimeMinutes,
            boolean mealWaiver,
            boolean restConfirmed,
            boolean restNotProvided,
            boolean waiverOnFile,
            String note,
            List<FindingDto> findings,
            Integer rateCents,
            Integer payCents,
            Integer premiumCents,
            String voidReason,
            List<BreakQuestionDto> breakQuestions,
            boolean breaksAssumed,
            boolean needsAttention,
            List<String> attentionReasons,
            List<AnswerDto> workerAnswers) {}

    public record SubmitRequest(
            String date,
            String start,
            String end,
            String mealStart,
            String mealEnd,
            Boolean mealWaiver,
            Boolean restsTaken,
            String note,
            List<AnswerDto> answers) {}

    public record WaiverStatus(boolean signed, String signedAt, String signedName, String textVersion, String text) {}
}
