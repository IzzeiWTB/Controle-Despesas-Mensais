package br.com.clareza.financeiro;

final class CsvExport {
    private CsvExport() {}

    static boolean isValid(String filename, String content) {
        return filename != null && filename.matches("clareza-\\d{4}-(0[1-9]|1[0-2])\\.csv")
            && content != null && !content.isEmpty() && content.length() <= 5_000_000;
    }
}
