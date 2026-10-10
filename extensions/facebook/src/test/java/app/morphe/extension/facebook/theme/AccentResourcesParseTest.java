/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Route five's table is read as AccentResources loads, before any colour route one resolves, so an
 * entry it can't read is left out instead of throwing out of the class's initializer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AccentResourcesParseTest {
    @Test
    public void anUnreadableEntryIsLeftOutAndTheRestAreKept() {
        List<AccentResources.Blue> blues = AccentResources.parse(
                "zz=ff0866ff:ACCENT;7f0601d4=ff0064d1/nope:BLUE_LINK;7f0601d5=ff0866ff:ACCENT,PRIMARY_BUTTON_BACKGROUND");
        assertEquals(1, blues.size());
        assertEquals(0x7f0601d5, blues.get(0).id);
        assertEquals(0xFF0866FF, blues.get(0).colour);
    }
}
