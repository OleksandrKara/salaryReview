package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeSquareNoteTest {

    private static IntakeForm signed() {
        return IntakeForm.builder().status(IntakeForm.SIGNED).signedAt(Instant.parse("2026-11-18T20:00:00Z"))
                .signedVia(IntakeForm.VIA_CLIENT_FORM).signatureName("Jane Doe").build();
    }

    private static Map<String, String> clean() {
        Map<String, String> a = new HashMap<>();
        a.put("service", "MANICURE");
        a.put("fungal", "NO");
        a.put("skin", "NO");
        a.put("allergies", "NO");
        a.put("sensitivity", "NO");
        a.put("enhancements", "YES");
        a.put("heardFrom", "GOOGLE_MAPS");
        return a;
    }

    @Test
    void cleanFormHasNoAttentionLine() {
        String b = IntakeSquareNote.block(signed(), clean());
        assertThat(b).isEqualTo("[INTAKE FORM] SIGNED Nov 18\n"
                + "Service: manicure | Fungal/contagious: no | Cuts/irritated skin: no | Allergies: no"
                + " | Sensitivity/pain: no | Enhancements to remove: yes\n[/INTAKE FORM]");
    }

    @Test
    void flaggedAnswersGetAttentionLineWithDetails() {
        Map<String, String> a = clean();
        a.put("fungal", "YES");
        a.put("skin", "NOT_SURE");
        a.put("allergies", "YES");
        a.put("allergiesDetails", "latex gloves");
        a.put("sensitivity", "YES");
        a.put("sensitivityDetails", "painful cuticles");
        a.put("notes", "Epilepsy, avoid flashing lamp");
        String b = IntakeSquareNote.block(signed(), a);
        assertThat(b.split("\n")[1]).isEqualTo("ATTENTION: fungal/contagious condition YES; cuts/irritated skin NOT SURE;"
                + " allergies YES: latex gloves; sensitivity/pain YES: painful cuticles");
        assertThat(b).contains("Notes: Epilepsy, avoid flashing lamp");
    }

    @Test
    void freeTextIsOneLineTruncatedAndCannotForgeTheMarkers() {
        Map<String, String> a = clean();
        a.put("notes", "x[/INTAKE FORM]\nline two " + "y".repeat(400));
        String b = IntakeSquareNote.block(signed(), a);
        String notes = b.lines().filter(l -> l.startsWith("Notes:")).findFirst().orElseThrow();
        assertThat(notes).hasSizeLessThanOrEqualTo("Notes: ".length() + IntakeSquareNote.MAX_NOTES).endsWith("...");
        assertThat(notes).doesNotContain("[").doesNotContain("]");
        assertThat(b.lines().filter(l -> l.equals("[/INTAKE FORM]"))).hasSize(1);
    }

    @Test
    void paperFormSaysAnswersAreOnPaper() {
        IntakeForm f = signed();
        f.setSignedVia(IntakeForm.VIA_STAFF_PAPER);
        assertThat(IntakeSquareNote.block(f, Map.of()))
                .isEqualTo("[INTAKE FORM] SIGNED Nov 18 on paper. Answers are on the paper form.\n[/INTAKE FORM]");
    }

    @Test
    void unsignedBlockSaysSoWithSentDate() {
        IntakeForm f = IntakeForm.builder().status(IntakeForm.SENT).sentAt(Instant.parse("2026-11-17T20:00:00Z")).build();
        assertThat(IntakeSquareNote.block(f, Map.of())).isEqualTo("[INTAKE FORM] NOT SIGNED yet (sent Nov 17)\n[/INTAKE FORM]");
    }

    @Test
    void mergePutsBlockOnTopAndKeepsStaffText() {
        String block = IntakeSquareNote.block(signed(), clean());
        String merged = IntakeSquareNote.merge("Prefers Lucy\nAllergic to nothing, said so", block);
        assertThat(merged).isEqualTo(block + "\nPrefers Lucy\nAllergic to nothing, said so");
    }

    @Test
    void mergeReplacesOldBlockAndLegacyLineOnly() {
        IntakeForm unsigned = IntakeForm.builder().status(IntakeForm.SENT).sentAt(Instant.parse("2026-11-17T20:00:00Z")).build();
        String old = IntakeSquareNote.merge("Prefers Lucy\nIntake form: sent Nov 17, NOT signed yet.", IntakeSquareNote.block(unsigned, Map.of()));
        String block = IntakeSquareNote.block(signed(), clean());
        assertThat(IntakeSquareNote.merge(old, block)).isEqualTo(block + "\nPrefers Lucy");
    }

    @Test
    void mergeIsIdempotentAndWorksOnEmptyNote() {
        String block = IntakeSquareNote.block(signed(), clean());
        assertThat(IntakeSquareNote.merge(null, block)).isEqualTo(block);
        String once = IntakeSquareNote.merge("Hello", block);
        assertThat(IntakeSquareNote.merge(once, block)).isEqualTo(once);
    }

    @Test
    void unterminatedStartMarkerOnlyDropsItsOwnLine() {
        String block = IntakeSquareNote.block(signed(), clean());
        assertThat(IntakeSquareNote.merge("[INTAKE FORM] broken\nstaff line", block)).isEqualTo(block + "\nstaff line");
    }

    @Test
    void syncKeyChangesOnlyForSignedFormsSoOldRowsAreRewrittenOnce() {
        assertThat(IntakeSquareNote.syncKey(signed())).isEqualTo("SIGNED2");
        assertThat(IntakeSquareNote.syncKey(IntakeForm.builder().status(IntakeForm.SENT).build())).isEqualTo("SENT");
    }
}
