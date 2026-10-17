/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */
package qupath.lib.objects.hierarchy;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestHierarchyListenerChanges {

    @Test
    void listenersAddedDuringNotificationStartWithTheNextEvent() {
        var hierarchy = new PathObjectHierarchy();
        var calls = new ArrayList<String>();
        var added = new AtomicBoolean();
        PathObjectHierarchyListener late = event -> calls.add("late");
        hierarchy.addListener(event -> {
            calls.add("first");
            if (added.compareAndSet(false, true))
                hierarchy.addListener(late);
        });
        hierarchy.addListener(event -> calls.add("last"));

        hierarchy.fireHierarchyChangedEvent(this);
        assertEquals(List.of("first", "last"), calls);
        calls.clear();
        hierarchy.fireHierarchyChangedEvent(this);
        assertEquals(List.of("first", "last", "late"), calls);
    }

    @Test
    void removingListenersDoesNotSkipOtherRecipientsOfTheCurrentEvent() {
        var hierarchy = new PathObjectHierarchy();
        var calls = new ArrayList<String>();
        PathObjectHierarchyListener removed = event -> calls.add("removed");
        PathObjectHierarchyListener selfRemoving = new PathObjectHierarchyListener() {
            @Override
            public void hierarchyChanged(qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent event) {
                calls.add("first");
                hierarchy.removeListener(this);
                hierarchy.removeListener(removed);
            }
        };
        hierarchy.addListener(selfRemoving);
        hierarchy.addListener(removed);
        hierarchy.addListener(event -> calls.add("last"));

        hierarchy.fireHierarchyChangedEvent(this);
        assertEquals(List.of("first", "removed", "last"), calls);
        calls.clear();
        hierarchy.fireHierarchyChangedEvent(this);
        assertEquals(List.of("last"), calls);
    }
}
