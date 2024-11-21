package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;


public class Stat {

    // Helper method to get permissions in octal format
    private static int getPermissionsOctal(BasicFileAttributes basicAttr, PosixFileAttributes attr) {
        int permissions = 0;

        // File type bits (first)
        if (basicAttr.isDirectory()) permissions |= 0040000;  // Directory
        if (basicAttr.isRegularFile()) permissions |= 0100000;  // Regular file
        if (basicAttr.isSymbolicLink()) permissions |= 0120000;  // Symbolic link

        // Then add permission bits
        Set<PosixFilePermission> perms = attr.permissions();

        if (perms.contains(PosixFilePermission.OWNER_READ)) permissions |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) permissions |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) permissions |= 0100;

        if (perms.contains(PosixFilePermission.GROUP_READ)) permissions |= 0040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) permissions |= 0020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) permissions |= 0010;

        if (perms.contains(PosixFilePermission.OTHERS_READ)) permissions |= 0004;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) permissions |= 0002;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) permissions |= 0001;

        return permissions;
    }

    public static RuntimeList stat(RuntimeScalar arg) {
        RuntimeList res = new RuntimeList();
        try {
            Path path = Paths.get(arg.toString());

            // Basic file attributes (similar to some Perl stat fields)
            BasicFileAttributes basicAttr = Files.readAttributes(path, BasicFileAttributes.class);

            // POSIX file attributes (for Unix-like systems)
            PosixFileAttributes posixAttr = Files.readAttributes(path, PosixFileAttributes.class);

            statInternal(res, basicAttr, posixAttr);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    public static RuntimeList lstat(RuntimeScalar arg) {
        RuntimeList res = new RuntimeList();
        try {
            Path path = Paths.get(arg.toString());

            // Basic attributes without following symlink
            BasicFileAttributes basicAttr = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            // POSIX attributes without following symlink
            PosixFileAttributes posixAttr = Files.readAttributes(path,
                    PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            statInternal(res, basicAttr, posixAttr);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    public static void statInternal(RuntimeList res, BasicFileAttributes basicAttr, PosixFileAttributes posixAttr) {
        // Emulating Perl's stat return list
        res.add(scalarUndef);                            // 0 dev (device number) - not directly available in Java
        res.add(scalarUndef);                            // 1 ino (inode number) - not directly available in Java
        res.add(getScalarInt(getPermissionsOctal(basicAttr, posixAttr))); // 2 mode (file mode/permissions)
        res.add(scalarUndef);                            // 3 nlink (number of hard links) - not easily obtainable in standard Java
        res.add(scalarUndef);                            // 4 uid (user ID) - posixAttr.owner().getName() returns String
        res.add(scalarUndef);                            // 5 gid (group ID) - posixAttr.group().getName() returns String
        res.add(scalarUndef);                            // 6 rdev (device identifier for special files) - not available
        res.add(getScalarInt(basicAttr.size()));         // 7 size (file size in bytes)
        res.add(getScalarInt(basicAttr.lastAccessTime().toMillis() / 1000)); // 8 atime (last access time)
        res.add(getScalarInt(basicAttr.lastModifiedTime().toMillis() / 1000)); // 9 mtime (last modified time)
        res.add(getScalarInt(basicAttr.creationTime().toMillis() / 1000));    // 10 ctime (file creation time)
        res.add(scalarUndef);                            // 11 blksize (preferred I/O block size) - not directly available
        res.add(scalarUndef);                            // 12 blocks (number of blocks) - not directly available
    }
}

