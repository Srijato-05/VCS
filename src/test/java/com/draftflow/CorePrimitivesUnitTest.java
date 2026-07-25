package com.draftflow;

import com.draftflow.core.Hasher;
import com.draftflow.core.ObjectType;
import com.draftflow.db.FileMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CorePrimitivesUnitTest {

    @Test
    public void testHasherHashComputation() {
        String input = "sample-string-input";
        String sha256 = Hasher.hash(input.getBytes());
        assertNotNull(sha256);
        assertEquals(64, sha256.length());
    }

    @Test
    public void testFileMetadataSerialization() {
        String path = "src/File1.java";
        FileMetadata meta = new FileMetadata(path, 10L, 100L, "hash1", "BLOB", 100644);
        String json = meta.toJson();
        assertNotNull(json);
        FileMetadata parsed = FileMetadata.fromJson(json);
        assertNotNull(parsed);
        assertEquals(path, parsed.getPath());
        assertEquals("hash1", parsed.getHash());
    }

    @Test
    public void testObjectTypeEnum() {
        for (ObjectType type : ObjectType.values()) {
            assertNotNull(type.name());
        }
    }
}
