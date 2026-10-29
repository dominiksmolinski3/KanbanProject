package pl.myproject.kanbanproject2.config;

public final class LogValues {

    private LogValues() {
    }

    public static String oneLine(String value) {
        return value == null ? null : value.replace('\n', '_').replace('\r', '_');
    }
}
