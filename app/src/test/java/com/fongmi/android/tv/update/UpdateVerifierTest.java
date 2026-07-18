package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class UpdateVerifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void calculatesAndChecksSha256() throws Exception {
        File file = temporaryFolder.newFile("update.apk");
        Files.writeString(file.toPath(), "pianduoduo");
        String expected = "0acef1860fcb921be29f32e8245029b822b1c8f3a3495b0df260acc76bfc34e4";

        assertEquals(expected, UpdateVerifier.sha256(file));
        assertTrue(UpdateVerifier.checksumMatches(file, expected.toUpperCase()));
        assertFalse(UpdateVerifier.checksumMatches(file, "00"));
        assertFalse(UpdateVerifier.checksumMatches(file, ""));
    }
}
