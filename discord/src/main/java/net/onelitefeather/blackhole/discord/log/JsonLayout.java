package net.onelitefeather.blackhole.discord.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSON object per log line: {@code ts, level, logger, thread, message} and, for an error with a throwable, the throwable's
 * class and its first few frames. The throwable's own message is deliberately left out, because a library may put the text it
 * choked on into it. The bot's own messages never carry message content, Ban IDs or Discord ids (a test asserts it); this layout is
 * the second line of defence for what libraries write.
 */
public final class JsonLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FRAMES = 6;

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("level", event.getLevel().toString());
        line.put("logger", event.getLoggerName());
        line.put("thread", event.getThreadName());
        line.put("message", event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            line.put("exception", throwable.getClassName());
            List<String> frames = new ArrayList<>();
            StackTraceElementProxy[] trace = throwable.getStackTraceElementProxyArray();
            for (int i = 0; trace != null && i < Math.min(trace.length, FRAMES); i++) {
                frames.add(trace[i].getStackTraceElement().toString());
            }
            line.put("frames", frames);
        }
        try {
            return JSON.writeValueAsString(line) + "\n";
        } catch (JsonProcessingException e) {
            return "{\"level\":\"ERROR\",\"message\":\"log line could not be written\"}\n";
        }
    }
}
