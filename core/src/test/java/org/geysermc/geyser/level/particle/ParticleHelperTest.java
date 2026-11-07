/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.level.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleHelperTest {
    private static final float DELTA = 1.0E-5F;

    @Test
    void noteColorMatchesBedrockNoteBlocks() {
        // Note 0
        assertNoteColor(0.0, 0.35F, 0.912917F, -0.212917F);
        // Note 6, a quarter turn of the colour wheel
        assertNoteColor(6.0 / 24.0, 1.0F, 0.025F, 0.025F);
        // Note 24 wraps back to the colour of note 0
        assertNoteColor(1.0, 0.35F, 0.912917F, -0.212917F);
    }

    private static void assertNoteColor(double note, float red, float green, float blue) {
        JsonObject variable = ParticleHelper.serializeNoteColor(note).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("variable.note_color", variable.get("name").getAsString());

        JsonObject value = variable.getAsJsonObject("value");
        assertEquals("member_array", value.get("type").getAsString());

        JsonArray members = value.getAsJsonArray("value");
        assertEquals(4, members.size());
        assertMember(members.get(0).getAsJsonObject(), ".r", red);
        assertMember(members.get(1).getAsJsonObject(), ".g", green);
        assertMember(members.get(2).getAsJsonObject(), ".b", blue);
        assertMember(members.get(3).getAsJsonObject(), ".a", 1.0F);
    }

    private static void assertMember(JsonObject member, String name, float expected) {
        assertEquals(name, member.get("name").getAsString());
        JsonObject value = member.getAsJsonObject("value");
        assertEquals("float", value.get("type").getAsString());
        assertEquals(expected, value.get("value").getAsFloat(), DELTA);
    }
}
