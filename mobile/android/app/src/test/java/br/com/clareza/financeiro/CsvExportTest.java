package br.com.clareza.financeiro;

import org.junit.Test;
import static org.junit.Assert.*;

public class CsvExportTest {
    @Test public void acceptsMonthlyCsv() {
        assertTrue(CsvExport.isValid("clareza-2026-10.csv", "\uFEFFData;Descrição\r\n2026-10-17;Almoço"));
    }
    @Test public void rejectsPathsInvalidMonthsAndOtherFiles() {
        for (String name : new String[] { "../clareza-2026-10.csv", "secret.txt", "clareza-2026-00.csv", "clareza-2026-13.csv", null }) {
            assertFalse(CsvExport.isValid(name, "csv"));
        }
    }
    @Test public void rejectsMissingOrOversizedContent() {
        assertFalse(CsvExport.isValid("clareza-2026-10.csv", null));
        assertFalse(CsvExport.isValid("clareza-2026-10.csv", ""));
        assertFalse(CsvExport.isValid("clareza-2026-10.csv", new String(new char[5_000_001])));
    }
}
