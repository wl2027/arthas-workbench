package com.alibaba.arthas.idea.workbench.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.eclipse.jifa.jfr.enums.Unit;

/**
 * Jifa 分析页的轻量格式化工具。
 */
final class JifaUiFormatters {

    private static final DecimalFormat DECIMAL =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private JifaUiFormatters() {}

    static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return DECIMAL.format(value);
    }

    static String formatPercent(double value) {
        return formatDouble(value) + "%";
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes >= GB) {
            return formatDouble((double) bytes / GB) + " GB";
        }
        if (bytes >= MB) {
            return formatDouble((double) bytes / MB) + " MB";
        }
        if (bytes >= KB) {
            return formatDouble((double) bytes / KB) + " KB";
        }
        return bytes + " B";
    }

    static String formatTimestampMillis(long timestampMillis) {
        if (timestampMillis <= 0) {
            return "-";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(timestampMillis));
    }

    static String formatDurationMillis(double durationMillis) {
        if (durationMillis < 0) {
            return "-";
        }
        if (durationMillis >= 1000) {
            return formatDouble(durationMillis / 1000.0) + " s";
        }
        return formatDouble(durationMillis) + " ms";
    }

    static String formatJfrValue(Unit unit, long value) {
        if (unit == null) {
            return String.valueOf(value);
        }
        return switch (unit) {
            case BYTE -> formatBytes(value);
            case NANO_SECOND -> formatNanos(value);
            case COUNT -> String.valueOf(value);
        };
    }

    private static String formatNanos(long nanos) {
        if (nanos >= 1_000_000_000L) {
            return formatDouble((double) nanos / 1_000_000_000L) + " s";
        }
        if (nanos >= 1_000_000L) {
            return formatDouble((double) nanos / 1_000_000L) + " ms";
        }
        if (nanos >= 1_000L) {
            return formatDouble((double) nanos / 1_000L) + " us";
        }
        return nanos + " ns";
    }
}
