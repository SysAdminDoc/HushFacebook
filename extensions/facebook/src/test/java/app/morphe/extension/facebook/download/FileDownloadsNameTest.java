/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Save files to Downloads (#590): the name a file attachment gets in the Downloads folder, from the
 * last segment of its address.
 */
public class FileDownloadsNameTest {
    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(text);
        return out.toString();
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    public void theNameTheFileWasPostedWithIsKept() {
        assertEquals("Quarterly report.pdf", FileDownloads.fileName("Quarterly report.pdf"));
        assertEquals("notes.txt", FileDownloads.fileName("notes.txt"));
        assertEquals("Lesson plan (final).docx", FileDownloads.fileName("Lesson plan (final).docx"));
    }

    @Test
    public void separatorsAndWhatAFatNameCantHoldBecomeAnUnderscore() {
        assertEquals("notes_1_draft.txt", FileDownloads.fileName("notes<1>|draft.txt"));
        assertEquals("a_b.pdf", FileDownloads.fileName("a/b.pdf"));
        assertEquals("a_b.pdf", FileDownloads.fileName("a\\b.pdf"));
        assertEquals("Budget_ Q1.xlsx", FileDownloads.fileName("Budget: Q1.xlsx"));
    }

    @Test
    public void aNameCantHideOrClimbOutOfDownloads() {
        assertEquals("hidden.txt", FileDownloads.fileName("..hidden.txt"));
        assertEquals("env", FileDownloads.fileName(" .env "));
        assertEquals("passwd", FileDownloads.fileName("../../passwd"));
    }

    @Test
    public void nothingWorthKeepingIsTheFallback() {
        for (String nothing : Arrays.asList(null, "", "   ", "...", "///", ". . .")) {
            assertEquals(String.valueOf(nothing), FileDownloads.FALLBACK, FileDownloads.fileName(nothing));
        }
    }

    @Test
    public void aLongNameIsCutFromItsBaseSoItsEndingStays() {
        String name = FileDownloads.fileName(repeat("a", 300) + ".pdf");
        assertEquals(repeat("a", 196) + ".pdf", name);
        assertEquals(FileDownloads.MAX_NAME_BYTES, bytes(name));
    }

    @Test
    public void anEndingTooLongToBeOneIsCutWithTheRest() {
        String name = FileDownloads.fileName(repeat("a", 250) + ".thisisnotanending");
        assertEquals(repeat("a", 200), name);
    }

    @Test
    public void aCutNeverSplitsACharacter() {
        // Two bytes each: 97 of them and ".docx" are 199 bytes, and a 98th wouldn't fit.
        String accented = String.valueOf((char) 0xE9);
        String name = FileDownloads.fileName(repeat(accented, 150) + ".docx");
        assertEquals(repeat(accented, 97) + ".docx", name);
        assertTrue(bytes(name) <= FileDownloads.MAX_NAME_BYTES);

        // Four bytes each, written as a surrogate pair: 49 and ".pdf" fill the 200 exactly.
        String document = new String(Character.toChars(0x1F4C4));
        name = FileDownloads.fileName(repeat(document, 60) + ".pdf");
        assertEquals(repeat(document, 49) + ".pdf", name);
        assertEquals(FileDownloads.MAX_NAME_BYTES, bytes(name));
    }

    @Test
    public void theByteCountIsUtf8s() {
        for (String text : Arrays.asList("", "report.pdf", String.valueOf((char) 0xE9), String.valueOf((char) 0x4E2D),
                new String(Character.toChars(0x1F4C4)))) {
            assertEquals(text, bytes(text), FileDownloads.utf8Length(text));
        }
    }

    @Test
    public void namingAGivenNameAgainChangesNothing() {
        for (String raw : Arrays.asList("Quarterly report.pdf", "notes<1>|draft.txt", "..hidden.txt",
                repeat("a", 300) + ".pdf", repeat(new String(Character.toChars(0x1F4C4)), 60) + ".pdf")) {
            String once = FileDownloads.fileName(raw);
            assertEquals(raw, once, FileDownloads.fileName(once));
        }
    }
}
