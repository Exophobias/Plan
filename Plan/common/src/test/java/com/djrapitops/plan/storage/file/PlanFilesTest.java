/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.storage.file;

import extension.FullSystemExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import utilities.TestResources;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author AuroraLS3
 */
@ExtendWith(FullSystemExtension.class)
class PlanFilesTest {

    @Test
    void parallelJarResourceStreamsReturnCompleteIndependentContent(PlanFiles files) throws Exception {
        byte[] expected = TestResources.getJarResourceAsBytes("/assets/plan/web/error.html");
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var reads = new ArrayList<Future<byte[]>>();
            for (int worker = 0; worker < workers; worker++) {
                reads.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Resource reads were not released");
                    return files.getResourceFromJar("web/error.html").asBytes();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "All readers must contend on the first resource load");
            start.countDown();
            for (Future<byte[]> read : reads) assertArrayEquals(expected, read.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    @Test
    void jarResourceStreamsHaveIndependentPositionsAndClosure(PlanFiles files) throws Exception {
        byte[] expected = TestResources.getJarResourceAsBytes("/assets/plan/config.yml");
        Resource resource = files.getResourceFromJar("config.yml");
        try (InputStream first = resource.asInputStream(); InputStream second = resource.asInputStream()) {
            assertEquals(expected[0] & 0xff, first.read());
            assertArrayEquals(expected, second.readAllBytes());
            second.close();
            assertArrayEquals(Arrays.copyOfRange(expected, 1, expected.length), first.readAllBytes());
        }
        assertArrayEquals(expected, resource.asBytes(), "Closing prior streams must not close a subsequent read");
    }

    @Test
    void jarResourceReadDoesNotRewriteAnUnrelatedMaterializedFile(PlanFiles files) throws Exception {
        Path control = files.getDataDirectory().resolve("assets/plan/web/error.html");
        Files.createDirectories(control.getParent());
        byte[] existing = "unrelated fixture bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(control, existing);
        assertArrayEquals(TestResources.getJarResourceAsBytes("/assets/plan/web/error.html"),
                files.getResourceFromJar("web/error.html").asBytes());
        assertArrayEquals(existing, Files.readAllBytes(control));
    }

    @Test
    void missingJarResourceRemainsAnExplicitFailureWithoutCreatingAFile(PlanFiles files) {
        assertThrows(FileNotFoundException.class, () -> files.getResourceFromJar("missing-resource-for-test.bin").asBytes());
        assertFalse(Files.exists(files.getDataDirectory().resolve("assets/plan/missing-resource-for-test.bin")));
    }

    @Test
    @DisplayName("getFileFromPluginFolder has no Path Traversal vulnerability")
    void getFileFromPluginFolderDoesNotAllowAbsolutePathTraversal(@TempDir Path tempDir, PlanFiles files) throws IOException {
        Path testFile = tempDir.resolve("file.db");
        Files.createDirectories(tempDir.getParent());
        Files.createFile(testFile);

        File file = files.getFileFromPluginFolder(testFile.toFile().getAbsolutePath());
        assertNotEquals(testFile.toFile().getAbsolutePath(), file.getAbsolutePath());
    }
}
