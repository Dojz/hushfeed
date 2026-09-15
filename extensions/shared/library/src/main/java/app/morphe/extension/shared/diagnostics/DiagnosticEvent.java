/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class DiagnosticEvent {
    /**
     * One formatter per thread, kept for the life of the thread.
     *
     * <p>{@link SimpleDateFormat} is not thread safe, so it cannot simply be a static, and
     * building one per call was costing a formatter, a time zone lookup and a pattern parse on
     * every logged line. An anonymous subclass rather than {@code ThreadLocal.withInitial},
     * which the payload's API 23 floor does not have.
     */
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat format =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format;
        }
    };

    public final DiagnosticCategory category;
    public final long timestamp;
    public final String thread;
    public final String source;
    public final String level;
    public final String message;

    /**
     * The line this event reads as, built once.
     *
     * <p>It was built on demand, and demand is more than it sounds: appending an event asks for
     * its length, evicting one asks again, the undo path asks twice more, and the export asks
     * once more, so the same line was formatted at least three times over its life. Every one of
     * those was a fresh formatter, and the first two happen while the buffer lock is held, so the
     * cost landed on whichever thread was logging and every other logging thread waited behind
     * it. Every event that is built is appended, so there is nothing to defer.
     */
    private final String formatted;

    public DiagnosticEvent(
            DiagnosticCategory category,
            long timestamp,
            String thread,
            String source,
            String level,
            String message
    ) {
        this.category = category;
        this.timestamp = timestamp;
        this.thread = thread;
        this.source = source;
        this.level = level;
        this.message = message;
        this.formatted = category.value + " | " + TIMESTAMP.get().format(new Date(timestamp))
                + " | " + thread + " | " + source + " | " + level + " | " + message;
    }

    public String format() {
        return formatted;
    }
}
