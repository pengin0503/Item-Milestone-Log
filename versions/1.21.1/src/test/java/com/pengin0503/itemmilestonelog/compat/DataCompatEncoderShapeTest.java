package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.MilestoneData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #156 / #99: guards the six 1.21.5-1.21.11 compat-variant {@code DataCompatImpl} codecs against
 * reintroducing the double-wrapped {@code {"data": {"data": ...}}} SavedData compound.
 *
 * <p>The variant sources live under {@code versions/1.21.1/compat-variants/} and are swapped into
 * a generated verify project by {@code tools/prepare-121x-harness.sh}; the CI profile jobs only
 * run the {@code jar} task, so a JUnit class that imports those variant classes would never run in
 * CI. This test therefore reads the six variant sources themselves (from the same main-source-dir
 * property Gradle passes to the other source-reading tests) and asserts the encoder body returns
 * the payload directly, plus a runtime NBT assertion of the single-wrapper invariant the encoder
 * relies on. It runs in the 1.21.1 {@code gradlew test} job in CI.
 */
class DataCompatEncoderShapeTest {
    private static final List<String> VARIANTS = List.of(
            "v121_5", "v121_6", "v121_7", "v121_8", "v121_9", "v121_11");

    private static Path resolveVariantsDir() {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (mainSourceDir != null && !mainSourceDir.isBlank()) {
            Path cur = Path.of(mainSourceDir).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve("compat-variants");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                cur = cur.getParent();
            }
        }
        Path fromVersionDir = Path.of("compat-variants");
        if (Files.isDirectory(fromVersionDir)) {
            return fromVersionDir;
        }
        Path fromRunDir = Path.of("..", "compat-variants");
        if (Files.isDirectory(fromRunDir)) {
            return fromRunDir;
        }
        throw new AssertionError("compat-variants directory not found; "
                + "set -Ditemmilestonelog.mainSourceDir=<version>/src/main/java");
    }

    /** Extracts the body of the first method whose signature contains {@code signature}. */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "method not found: " + signature);
        int open = source.indexOf('{', at);
        assertTrue(open >= 0, "method body not found: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced method body: " + signature);
    }

    @Test
    void allSixVariantEncodersWriteThePayloadDirectly() throws IOException {
        Path variants = resolveVariantsDir();
        for (String variant : VARIANTS) {
            Path sourceFile = variants.resolve(variant).resolve("com/pengin0503/itemmilestonelog")
                    .resolve("compat").resolve(variant).resolve("DataCompatImpl.java");
            assertTrue(Files.isRegularFile(sourceFile), "missing variant source: " + sourceFile);
            String source = Files.readString(sourceFile);

            String encode = methodBody(source, "encode(MilestoneData data");
            assertTrue(encode.contains("data.save(new CompoundTag(), provider)"),
                    variant + ": encoder must return the payload directly");
            assertTrue(encode.contains("return DataResult.success(result)"),
                    variant + ": encoder must succeed with the unwrapped payload result");
            assertFalse(encode.contains("put(\"data\""),
                    variant + ": encoder must not add its own data wrapper (#99)");
            assertFalse(encode.contains("createMap"),
                    variant + ": encoder must not build an extra map");
            assertFalse(encode.contains("RecordBuilder"),
                    variant + ": encoder must not re-wrap through a RecordBuilder");

            // The decoder still accepts the legacy double-wrapped file for saved-data compatibility.
            String decode = methodBody(source, "decode(DynamicOps<T> ops");
            assertTrue(decode.contains("root.contains(\"data\")"),
                    variant + ": decoder must keep accepting legacy data/data files");
        }
    }

    @Test
    void dimensionDataStorageStyleWrapHasNoNestedDataCompound() {
        // This is the NBT shape the direct-payload encoder produces once DimensionDataStorage adds
        // its single {"data": ...} layer. The variants share MilestoneData.save(), so the runtime
        // assertion holds for all six of them.
        CompoundTag payload = new MilestoneData().save(new CompoundTag(), null);
        assertFalse(payload.contains("data"), "the payload itself must not contain a data wrapper");

        CompoundTag savedDataFile = new CompoundTag();
        savedDataFile.put("data", payload);
        assertTrue(savedDataFile.contains("data"));
        assertFalse(savedDataFile.getCompound("data").contains("data"),
                "no nested data/data wrapper in the written SavedData NBT");
    }
}
