package qupath.ext.recorder;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TestFxResponsivenessMonitor {
    @Test void blockedUiProducesBoundedStacksAndARecoveryDuration() {
        var callbacks = new ArrayDeque<Runnable>(); var time = new AtomicLong();
        var events = new ArrayList<String>(); var fields = new ArrayList<Map<String, Object>>();
        try (var monitor = new FxResponsivenessMonitor(callbacks::add, time::get, () -> "nativeSave()",
                (name, values) -> { events.add(name); fields.add(values); })) {
            monitor.tick(); assertEquals(1, callbacks.size());
            for (int second = 1; second <= 12; second++) { time.set(second * 1_000_000_000L); monitor.tick(); }
            assertEquals(1, callbacks.size()); assertEquals(3, events.size());
            assertEquals("nativeSave()", fields.getFirst().get("fxStack"));
            callbacks.remove().run();
            assertEquals("ui.resumed", events.getLast()); assertEquals(12000L, fields.getLast().get("durationMs"));
            monitor.tick(); callbacks.remove().run(); assertEquals(4, events.size());
        }
    }
    @Test void stoppedRecorderIgnoresPendingPulsesAndSchedulesNoMore() {
        var callbacks = new ArrayDeque<Runnable>();
        var monitor = new FxResponsivenessMonitor(callbacks::add, () -> 0, () -> "", (n, v) -> fail("Unexpected event"));
        monitor.tick(); monitor.close(); callbacks.remove().run(); monitor.tick();
        assertTrue(callbacks.isEmpty());
    }
}
