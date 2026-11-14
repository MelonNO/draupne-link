package de.droiddrone.pc;

import java.util.List;

/** Tests elsewhere: NameService's package-private settings. */
public final class NameServiceAccess {
    private NameServiceAccess() {
    }

    /** No virtual fleet: the drones a fleet program running on this PC announces stay out of the test's scans. */
    public static void noVirtualFleet(NameService service) {
        service.setVirtualDrones(List::of);
    }
}
