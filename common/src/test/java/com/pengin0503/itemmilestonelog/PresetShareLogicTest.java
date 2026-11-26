package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetShareLogicTest {
    private static TargetKey key(String itemId) {
        return TargetKey.of(itemId);
    }

    private static TargetKey key(String itemId, int count) {
        return TargetKey.of(itemId, count);
    }

    private static Map<String, String> localized(String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return values;
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PresetShareLogic.ShareData bareData(String id, List<TargetKey> items) {
        return new PresetShareLogic.ShareData(id, PresetName.absent(), items, PresetName.absent(),
                null, 0, null, List.of(), List.of(), List.of(), null);
    }

    private static PresetShareLogic.ShareData fullData() {
        return new PresetShareLogic.ShareData(
                "shared_set",
                PresetName.ofLocalized(localized("en_us", "Shared Set", "ja_jp", "共有セット")),
                List.of(key("minecraft:diamond", 5), key("minecraft:iron_ingot")),
                PresetName.ofPlain("A test preset"),
                "Endgame",
                7,
                3,
                List.of("allthetweaks", "curios"),
                List.of(new PresetGroup("ores", PresetName.ofPlain("Ores"),
                        List.of(key("minecraft:diamond", 5)))),
                List.of("next_a", "next_b"),
                2.5d);
    }

    @Test
    void encodesPrefixUnpaddedUrlSafeAndCanonicalKeyOrder() {
        String code = PresetShareLogic.encode(fullData());

        assertTrue(code.startsWith("IML1:"));
        String payload = code.substring("IML1:".length());
        assertFalse(payload.contains("="), "Base64URL payload must be unpadded");
        assertFalse(payload.contains("+"), "Base64URL payload must not use the standard alphabet");
        assertFalse(payload.contains("/"), "Base64URL payload must not use the standard alphabet");

        String json = PresetShareLogic.canonicalJson(fullData());
        assertTrue(json.startsWith("{\"id\":\"shared_set\",\"name\":{\"en_us\":\"Shared Set\",\"ja_jp\":\"共有セット\"}"));
        assertTrue(json.indexOf("\"id\"") < json.indexOf("\"name\""));
        assertTrue(json.indexOf("\"name\"") < json.indexOf("\"description\""));
        assertTrue(json.indexOf("\"description\"") < json.indexOf("\"items\""));
    }

    @Test
    void roundTripReturnsTheSameCanonicalJsonIdAndItemCount() {
        PresetShareLogic.ShareData data = fullData();
        String code = PresetShareLogic.encode(data);

        PresetShareLogic.Decoded decoded = PresetShareLogic.decode(code, id -> false);

        assertEquals(PresetShareLogic.Status.OK, decoded.status());
        assertEquals(data.id(), decoded.id());
        assertEquals(2, decoded.itemCount());
        assertEquals(PresetShareLogic.canonicalJson(data), decoded.json());
        assertTrue(decoded.json().contains("\"item\":\"minecraft:diamond\",\"count\":5"));
    }

    @Test
    void omittedDefaultsKeepTheDocumentMinimal() {
        String json = PresetShareLogic.canonicalJson(bareData("minimal", List.of(key("minecraft:dirt"))));

        assertEquals("{\"id\":\"minimal\",\"items\":[\"minecraft:dirt\"]}", json);
        assertEquals(PresetShareLogic.Status.OK,
                PresetShareLogic.decode(PresetShareLogic.PREFIX + base64Url(json), null).status());
    }

    @Test
    void sharedItemsUseStringsForCountOneAndObjectsForHigherCounts() {
        String json = PresetShareLogic.canonicalJson(
                bareData("counts", List.of(key("minecraft:dirt"), key("minecraft:stone", 32))));

        assertTrue(json.contains("\"items\":[\"minecraft:dirt\",{\"item\":\"minecraft:stone\",\"count\":32}]"));
    }

    @Test
    void badPrefixIsRejected() {
        assertEquals(PresetShareLogic.Status.INVALID_PREFIX,
                PresetShareLogic.decode("XYZ:" + base64Url("{}"), null).status());
        assertEquals(PresetShareLogic.Status.INVALID_PREFIX,
                PresetShareLogic.decode(null, null).status());
        assertEquals(PresetShareLogic.Status.INVALID_PREFIX,
                PresetShareLogic.decode("", null).status());
    }

    @Test
    void corruptBase64IsRejected() {
        assertEquals(PresetShareLogic.Status.INVALID_CODE,
                PresetShareLogic.decode("IML1:!!!!", null).status());
        assertEquals(PresetShareLogic.Status.INVALID_CODE,
                PresetShareLogic.decode("IML1:", null).status());
    }

    @Test
    void corruptJsonIsRejected() {
        assertEquals(PresetShareLogic.Status.INVALID_JSON,
                PresetShareLogic.decode("IML1:" + base64Url("{ this is not json"), null).status());
        assertEquals(PresetShareLogic.Status.INVALID_JSON,
                PresetShareLogic.decode("IML1:" + base64Url("[]"), null).status());
        assertEquals(PresetShareLogic.Status.INVALID_JSON,
                PresetShareLogic.decode("IML1:" + base64Url("{\"id\":\"x\",\"items\":\"nope\"}"), null).status());
    }

    @Test
    void missingOrBlankIdIsRejected() {
        PresetShareLogic.Decoded missing = PresetShareLogic.decode(
                "IML1:" + base64Url("{\"items\":[\"minecraft:dirt\"]}"), null);
        assertEquals(PresetShareLogic.Status.INVALID_ID, missing.status());
        assertNull(missing.id());

        PresetShareLogic.Decoded blank = PresetShareLogic.decode(
                "IML1:" + base64Url("{\"id\":\"  \",\"items\":[\"minecraft:dirt\"]}"), null);
        assertEquals(PresetShareLogic.Status.INVALID_ID, blank.status());
        assertEquals("", blank.id(), "The decoded (trimmed) ID is still reported");

        PresetShareLogic.Decoded nonString = PresetShareLogic.decode(
                "IML1:" + base64Url("{\"id\":5,\"items\":[\"minecraft:dirt\"]}"), null);
        assertEquals(PresetShareLogic.Status.INVALID_ID, nonString.status());
        assertNull(nonString.id());
    }

    @Test
    void invalidIdFormatIsRejectedBeforeTheCollisionPredicateRuns() {
        // Regression for #98: the collision predicate resolves the ID as a path segment, so it
        // must never run for an invalid ID (traversal, separators, control characters, length).
        for (String id : List.of(
                "../../escape",
                "a/b",
                "Upper",
                "has:colon",
                "has space",
                "a*b",
                "x".repeat(33),
                "",
                "bad\u0000id")) {
            String code = PresetShareLogic.encode(bareData(id, List.of(key("minecraft:dirt"))));
            PresetShareLogic.Decoded decoded = PresetShareLogic.decode(code, invalid -> {
                throw new AssertionError("idExists must not run for invalid id: " + invalid);
            });
            assertEquals(PresetShareLogic.Status.INVALID_ID, decoded.status(), id);
            assertEquals(id, decoded.id(), "The rejected ID must be preserved: " + id);
            assertNull(decoded.json());
        }
    }

    @Test
    void validIdStillRunsTheCollisionPredicateWithTheResolvedId() {
        AtomicReference<String> seen = new AtomicReference<>();
        PresetShareLogic.Decoded decoded = PresetShareLogic.decode(
                PresetShareLogic.encode(bareData("valid_id-2", List.of(key("minecraft:dirt")))),
                id -> {
                    seen.set(id);
                    return false;
                });
        assertEquals(PresetShareLogic.Status.OK, decoded.status());
        assertEquals("valid_id-2", seen.get());
    }

    @Test
    void deeplyNestedJsonIsRejectedAsInvalidJsonInsteadOfEscaping() throws Exception {
        // Regression for #98: JsonParser.parseString can throw StackOverflowError. Run on a small
        // stack so the nesting is guaranteed to overflow; the decoder must convert it to a status.
        String json = "[".repeat(6_000) + "]".repeat(6_000);
        String code = "IML1:" + base64Url(json);
        assertTrue(code.length() < PresetShareLogic.MAX_CODE_CHARS, "fixture must fit the code cap");
        AtomicReference<PresetShareLogic.Status> status = new AtomicReference<>();
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                status.set(PresetShareLogic.decode(code, null).status());
            } catch (Throwable t) {
                escaped.set(t);
            }
        }, "deep-json-decode", 128 * 1024);
        thread.start();
        thread.join();
        assertNull(escaped.get(), () -> "decode leaked " + escaped.get());
        assertEquals(PresetShareLogic.Status.INVALID_JSON, status.get());
    }

    @Test
    void canonicalJsonOmitsNonFiniteOrNegativeUnlockAfterHoursAndRequiresAnId() {
        for (Double value : List.of(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1.0d, -0.5d)) {
            PresetShareLogic.ShareData data = new PresetShareLogic.ShareData(
                    "unlock_check", PresetName.absent(), List.of(key("minecraft:dirt")),
                    PresetName.absent(), null, 0, null, List.of(), List.of(), List.of(), value);
            assertFalse(PresetShareLogic.canonicalJson(data).contains("unlockAfterHours"),
                    "Non-finite/negative unlockAfterHours must be omitted: " + value);
        }
        PresetShareLogic.ShareData zero = new PresetShareLogic.ShareData(
                "unlock_check", PresetName.absent(), List.of(key("minecraft:dirt")),
                PresetName.absent(), null, 0, null, List.of(), List.of(), List.of(), 0.0d);
        assertTrue(PresetShareLogic.canonicalJson(zero).contains("\"unlockAfterHours\":0.0"));

        assertThrows(NullPointerException.class, () -> new PresetShareLogic.ShareData(
                null, PresetName.absent(), List.of(key("minecraft:dirt")),
                PresetName.absent(), null, 0, null, List.of(), List.of(), List.of(), null));
    }

    @Test
    void zeroItemPresetIsRejected() {
        assertEquals(PresetShareLogic.Status.EMPTY_ITEMS,
                PresetShareLogic.decode(PresetShareLogic.encode(bareData("empty", List.of())), null).status());
    }

    @Test
    void itemCapIsRejectedAtTheBoundary() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < PresetShareLogic.MAX_ITEMS; i++) {
            ids.add("\"minecraft:dirt\"");
        }
        String atCap = "{\"id\":\"cap\",\"items\":[" + String.join(",", ids) + "]}";
        assertEquals(PresetShareLogic.Status.OK,
                PresetShareLogic.decode("IML1:" + base64Url(atCap), null).status());

        ids.add("\"minecraft:dirt\"");
        String overCap = "{\"id\":\"cap\",\"items\":[" + String.join(",", ids) + "]}";
        assertEquals(PresetShareLogic.Status.TOO_MANY_ITEMS,
                PresetShareLogic.decode("IML1:" + base64Url(overCap), null).status());
    }

    @Test
    void codeLengthCapIsRejectedBeforeDecoding() {
        String tooLong = "IML1:" + "A".repeat(PresetShareLogic.MAX_CODE_CHARS);
        assertEquals(PresetShareLogic.Status.TOO_LONG,
                PresetShareLogic.decode(tooLong, null).status());

        StringBuilder exact = new StringBuilder("IML1:");
        while (exact.length() < PresetShareLogic.MAX_CODE_CHARS) {
            exact.append('A');
        }
        assertEquals(PresetShareLogic.MAX_CODE_CHARS, exact.length());
        // Length itself is accepted; the all-'A' payload then fails as invalid JSON.
        assertNotEquals(PresetShareLogic.Status.TOO_LONG,
                PresetShareLogic.decode(exact.toString(), null).status());
    }

    @Test
    void idCollisionKeepsTheResolvedIdForTheErrorMessage() {
        String code = PresetShareLogic.encode(fullData());

        PresetShareLogic.Decoded decoded = PresetShareLogic.decode(code, "shared_set"::equals);

        assertEquals(PresetShareLogic.Status.ID_COLLISION, decoded.status());
        assertEquals("shared_set", decoded.id(), "The collided ID is needed for the message (#98)");
        assertNull(decoded.json());
        assertEquals(0, decoded.itemCount());
    }
}
