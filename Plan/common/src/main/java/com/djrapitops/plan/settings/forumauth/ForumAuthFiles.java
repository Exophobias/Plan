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
package com.djrapitops.plan.settings.forumauth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Secret-safe same-directory durability, with exact-source checks before replacement. */
final class ForumAuthFiles {

    private ForumAuthFiles() { }

    static void backup(Path file, byte[] source, int version) throws IOException {
        Path backup = privateTemporary(file, ".v" + version + ".bak-");
        boolean complete = false;
        try {
            writeForced(backup, source);
            ForumAuthConfigLoader.checkSource(file, source);
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(backup);
        }
    }

    static void replace(Path file, byte[] replacement, byte[] expected) throws IOException {
        Path temporary = privateTemporary(file, ".tmp-");
        try {
            writeForced(temporary, replacement);
            if (expected == null) {
                // A same-filesystem hard link atomically installs a fresh file and cannot overwrite
                // a concurrent administrator's creation, unlike ATOMIC_MOVE's platform-dependent case.
                Files.createLink(file, temporary);
            } else {
                ForumAuthConfigLoader.checkSource(file, expected);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path privateTemporary(Path file, String suffix) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName() + suffix, "");
        try {
            ownerOnly(temporary);
            return temporary;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static void writeForced(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void ownerOnly(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw ForumAuthConfig.invalid("filesystem cannot restrict credential files to their owner");
        AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(owner));
    }
}
