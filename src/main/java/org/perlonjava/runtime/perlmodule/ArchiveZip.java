package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * Archive::Zip module implementation for PerlOnJava.
 * This class provides zip file handling using Java's java.util.zip package.
 *
 * Implements core Archive::Zip functionality:
 * - Reading zip files
 * - Listing members
 * - Extracting members
 * - Adding new members (files/strings)
 * - Writing zip files
 */
public class ArchiveZip extends PerlModuleBase {

    // Keys for internal hash storage
    private static final String MEMBERS_KEY = "_members";
    private static final String FILENAME_KEY = "_filename";
    private static final String COMMENT_KEY = "_zipfileComment";

    // Constants (matching Archive::Zip)
    public static final int AZ_OK = 0;
    public static final int AZ_STREAM_END = 1;
    public static final int AZ_ERROR = 2;
    public static final int AZ_FORMAT_ERROR = 3;
    public static final int AZ_IO_ERROR = 4;

    public static final int COMPRESSION_STORED = 0;
    public static final int COMPRESSION_DEFLATED = 8;
    public static final int COMPRESSION_LEVEL_NONE = 0;
    public static final int COMPRESSION_LEVEL_DEFAULT = -1;
    public static final int COMPRESSION_LEVEL_FASTEST = 1;
    public static final int COMPRESSION_LEVEL_BEST_COMPRESSION = 9;

    public ArchiveZip() {
        super("Archive::Zip", false);
    }

    public static void initialize() {
        ArchiveZip az = new ArchiveZip();
        try {
            // Archive methods
            az.registerMethod("new", "newArchive", null);
            az.registerMethod("read", null);
            az.registerMethod("readFromFileHandle", null);
            az.registerMethod("zipfileComment", null);
            az.registerMethod("writeToFileNamed", null);
            az.registerMethod("writeToFileHandle", null);
            az.registerMethod("members", null);
            az.registerMethod("memberNames", null);
            az.registerMethod("numberOfMembers", null);
            az.registerMethod("memberNamed", null);
            az.registerMethod("membersMatching", null);
            az.registerMethod("addFile", null);
            az.registerMethod("addString", null);
            az.registerMethod("addDirectory", null);
            az.registerMethod("extractMember", null);
            az.registerMethod("extractMemberWithoutPaths", null);
            az.registerMethod("extractTree", null);
            az.registerMethod("removeMember", null);

            // Member methods (called on member objects)
            az.registerMethod("fileName", null);
            az.registerMethod("contents", null);
            az.registerMethod("isDirectory", null);
            az.registerMethod("uncompressedSize", null);
            az.registerMethod("compressedSize", null);
            az.registerMethod("compressionMethod", null);
            az.registerMethod("lastModTime", null);
            az.registerMethod("lastModFileDateTime", null);
            az.registerMethod("crc32", null);
            az.registerMethod("crc", "crc32", null);  // alias for crc32
            az.registerMethod("externalFileName", null);
            az.registerMethod("versionNeededToExtract", null);
            az.registerMethod("bitFlag", null);
            az.registerMethod("fileComment", null);

            // Constants
            az.registerMethod("AZ_OK", null);
            az.registerMethod("AZ_STREAM_END", null);
            az.registerMethod("AZ_ERROR", null);
            az.registerMethod("AZ_FORMAT_ERROR", null);
            az.registerMethod("AZ_IO_ERROR", null);
            az.registerMethod("COMPRESSION_STORED", null);
            az.registerMethod("COMPRESSION_DEFLATED", null);
            az.registerMethod("COMPRESSION_LEVEL_NONE", null);
            az.registerMethod("COMPRESSION_LEVEL_DEFAULT", null);
            az.registerMethod("COMPRESSION_LEVEL_FASTEST", null);
            az.registerMethod("COMPRESSION_LEVEL_BEST_COMPRESSION", null);

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Archive::Zip method: " + e.getMessage());
        }
    }

    // Constants
    public static RuntimeList AZ_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AZ_OK).getList();
    }

    public static RuntimeList AZ_STREAM_END(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AZ_STREAM_END).getList();
    }

    public static RuntimeList AZ_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AZ_ERROR).getList();
    }

    public static RuntimeList AZ_FORMAT_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AZ_FORMAT_ERROR).getList();
    }

    public static RuntimeList AZ_IO_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AZ_IO_ERROR).getList();
    }

    public static RuntimeList COMPRESSION_STORED(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_STORED).getList();
    }

    public static RuntimeList COMPRESSION_DEFLATED(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_DEFLATED).getList();
    }

    public static RuntimeList COMPRESSION_LEVEL_NONE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_LEVEL_NONE).getList();
    }

    public static RuntimeList COMPRESSION_LEVEL_DEFAULT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_LEVEL_DEFAULT).getList();
    }

    public static RuntimeList COMPRESSION_LEVEL_FASTEST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_LEVEL_FASTEST).getList();
    }

    public static RuntimeList COMPRESSION_LEVEL_BEST_COMPRESSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(COMPRESSION_LEVEL_BEST_COMPRESSION).getList();
    }

    /**
     * Create a new Archive::Zip object.
     * Usage: my $zip = Archive::Zip->new();
     *        my $zip = Archive::Zip->new('file.zip');
     */
    public static RuntimeList newArchive(RuntimeArray args, int ctx) {
        RuntimeHash self = new RuntimeHash();
        RuntimeArray membersArray = new RuntimeArray();
        self.put(MEMBERS_KEY, membersArray.createReference());

        RuntimeScalar ref = self.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar("Archive::Zip"));

        // If a filename is provided, read it
        if (args.size() > 1) {
            RuntimeScalar filename = args.get(1);
            if (filename.type != RuntimeScalarType.UNDEF) {
                self.put(FILENAME_KEY, filename);
                RuntimeArray readArgs = new RuntimeArray();
                RuntimeArray.push(readArgs, ref);
                RuntimeArray.push(readArgs, filename);
                RuntimeList result = read(readArgs, RuntimeContextType.SCALAR);
                int status = result.scalar().getInt();
                if (status != AZ_OK) {
                    return scalarUndef.getList();
                }
            }
        }

        return ref.getList();
    }

    /**
     * Read a zip file.
     * Usage: $status = $zip->read('file.zip');
     * Returns: AZ_OK on success, error code on failure
     */
    public static RuntimeList read(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String filename = args.get(1).toString();

        try {
            RuntimeArray members = getMembers(self);
            members.undefine(); // Clear existing members

            Path path = Paths.get(filename);
            if (!Files.exists(path)) {
                return new RuntimeScalar(AZ_IO_ERROR).getList();
            }

            try (ZipFile zipFile = new ZipFile(filename)) {
                // Store the zipfile comment
                String comment = zipFile.getComment();
                if (comment != null) {
                    self.put(COMMENT_KEY, new RuntimeScalar(comment));
                } else {
                    self.put(COMMENT_KEY, scalarUndef);
                }
                
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();

                    // Create member object
                    RuntimeHash member = createMemberFromEntry(zipFile, entry);
                    RuntimeScalar memberRef = member.createReference();
                    ReferenceOperators.bless(memberRef, new RuntimeScalar("Archive::Zip::Member"));

                    RuntimeArray.push(members, memberRef);
                }
            }

            self.put(FILENAME_KEY, new RuntimeScalar(filename));
            return new RuntimeScalar(AZ_OK).getList();

        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        } catch (Exception e) {
            return new RuntimeScalar(AZ_FORMAT_ERROR).getList();
        }
    }

    /**
     * Read a zip file from a filehandle.
     * Usage: $status = $zip->readFromFileHandle($fh);
     * Returns: AZ_OK on success, error code on failure
     */
    public static RuntimeList readFromFileHandle(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fhRef = args.get(1);

        try {
            RuntimeIO fh = RuntimeIO.getRuntimeIO(fhRef);
            if (fh == null) {
                return new RuntimeScalar(AZ_IO_ERROR).getList();
            }

            RuntimeArray members = getMembers(self);
            members.undefine(); // Clear existing members

            // Read all data from the filehandle into a byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            // Read in chunks until EOF
            int chunkSize = 8192;
            while (!fh.ioHandle.eof().getBoolean()) {
                RuntimeScalar data = fh.ioHandle.read(chunkSize, StandardCharsets.ISO_8859_1);
                if (!data.getDefinedBoolean()) {
                    break;
                }
                String dataStr = data.toString();
                if (dataStr.isEmpty()) {
                    break;
                }
                // Convert string back to bytes using ISO_8859_1 to preserve byte values
                baos.write(dataStr.getBytes(StandardCharsets.ISO_8859_1));
            }
            
            byte[] zipData = baos.toByteArray();
            if (zipData.length == 0) {
                return new RuntimeScalar(AZ_IO_ERROR).getList();
            }

            // Create a ZipInputStream from the byte array
            try (ByteArrayInputStream bais = new ByteArrayInputStream(zipData);
                 ZipInputStream zis = new ZipInputStream(bais)) {
                
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    // Read entry contents
                    ByteArrayOutputStream entryBaos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        entryBaos.write(buffer, 0, bytesRead);
                    }
                    
                    // Create member object
                    RuntimeHash member = new RuntimeHash();
                    member.put("_name", new RuntimeScalar(entry.getName()));
                    member.put("_externalFileName", new RuntimeScalar(""));
                    member.put("_isDirectory", entry.isDirectory() ? scalarTrue : scalarFalse);
                    member.put("_uncompressedSize", new RuntimeScalar(entry.getSize() >= 0 ? entry.getSize() : entryBaos.size()));
                    member.put("_compressedSize", new RuntimeScalar(entry.getCompressedSize() >= 0 ? entry.getCompressedSize() : entryBaos.size()));
                    member.put("_compressionMethod", new RuntimeScalar(entry.getMethod()));
                    
                    // Store Unix timestamp (seconds since epoch) for lastModTime
                    long timeMillis = entry.getTime();
                    member.put("_lastModTime", new RuntimeScalar(timeMillis >= 0 ? timeMillis / 1000 : 0));
                    // Store raw MS-DOS format for lastModFileDateTime
                    member.put("_lastModFileDateTime", new RuntimeScalar(getRawDosTime(entry)));
                    
                    member.put("_crc32", new RuntimeScalar(entry.getCrc() >= 0 ? entry.getCrc() : 0));
                    
                    // Additional fields for ExifTool compatibility
                    int versionNeeded = entry.getMethod() == ZipEntry.STORED ? 10 : 20;
                    member.put("_versionNeededToExtract", new RuntimeScalar(versionNeeded));
                    member.put("_bitFlag", new RuntimeScalar(0));
                    String comment = entry.getComment();
                    member.put("_fileComment", comment != null ? new RuntimeScalar(comment) : new RuntimeScalar(""));
                    
                    // Store contents
                    String contents = new String(entryBaos.toByteArray(), StandardCharsets.ISO_8859_1);
                    member.put("_contents", new RuntimeScalar(contents));
                    
                    RuntimeScalar memberRef = member.createReference();
                    ReferenceOperators.bless(memberRef, new RuntimeScalar("Archive::Zip::Member"));
                    RuntimeArray.push(members, memberRef);
                    
                    zis.closeEntry();
                }
            }

            // ZipInputStream doesn't provide access to the zipfile comment
            // Set it to empty string (not undef) for compatibility
            self.put(COMMENT_KEY, new RuntimeScalar(""));

            return new RuntimeScalar(AZ_OK).getList();

        } catch (java.util.zip.ZipException e) {
            return new RuntimeScalar(AZ_FORMAT_ERROR).getList();
        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        } catch (Exception e) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }
    }

    /**
     * Get the zip file comment.
     * Usage: $comment = $zip->zipfileComment();
     * Returns: The comment string or undef if not set.
     */
    public static RuntimeList zipfileComment(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar comment = self.get(COMMENT_KEY);
        
        if (comment == null) {
            return scalarUndef.getList();
        }
        return comment.getList();
    }

    /**
     * Write zip to a file.
     * Usage: $status = $zip->writeToFileNamed('output.zip');
     */
    public static RuntimeList writeToFileNamed(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String filename = args.get(1).toString();

        try {
            RuntimeArray members = getMembers(self);

            try (FileOutputStream fos = new FileOutputStream(filename);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                for (int i = 0; i < members.size(); i++) {
                    RuntimeHash member = members.get(i).hashDeref();
                    writeMemberToZip(zos, member);
                }
            }

            return new RuntimeScalar(AZ_OK).getList();

        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        } catch (Exception e) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }
    }

    /**
     * Write zip to a filehandle.
     * Usage: $status = $zip->writeToFileHandle($fh);
     */
    public static RuntimeList writeToFileHandle(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fhRef = args.get(1);

        try {
            RuntimeIO fh = RuntimeIO.getRuntimeIO(fhRef);
            if (fh == null) {
                return new RuntimeScalar(AZ_ERROR).getList();
            }

            // Create a ByteArrayOutputStream to collect the zip data
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                RuntimeArray members = getMembers(self);
                for (int i = 0; i < members.size(); i++) {
                    RuntimeHash member = members.get(i).hashDeref();
                    writeMemberToZip(zos, member);
                }
            }

            // Write to filehandle
            byte[] data = baos.toByteArray();
            String dataStr = new String(data, StandardCharsets.ISO_8859_1);
            fh.ioHandle.write(dataStr);

            return new RuntimeScalar(AZ_OK).getList();

        } catch (Exception e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        }
    }

    /**
     * Get list of all members.
     * Usage: @members = $zip->members();
     */
    public static RuntimeList members(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeArray members = getMembers(self);

        RuntimeList result = new RuntimeList();
        for (int i = 0; i < members.size(); i++) {
            result.add(members.get(i));
        }
        return result;
    }

    /**
     * Get list of all member names.
     * Usage: @names = $zip->memberNames();
     */
    public static RuntimeList memberNames(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeArray members = getMembers(self);

        RuntimeList result = new RuntimeList();
        for (int i = 0; i < members.size(); i++) {
            RuntimeHash member = members.get(i).hashDeref();
            result.add(member.get("_name"));
        }
        return result;
    }

    /**
     * Get number of members.
     * Usage: $count = $zip->numberOfMembers();
     */
    public static RuntimeList numberOfMembers(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeArray members = getMembers(self);
        return new RuntimeScalar(members.size()).getList();
    }

    /**
     * Get a member by name.
     * Usage: $member = $zip->memberNamed('path/to/file.txt');
     */
    public static RuntimeList memberNamed(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String name = args.get(1).toString();
        RuntimeArray members = getMembers(self);

        for (int i = 0; i < members.size(); i++) {
            RuntimeHash member = members.get(i).hashDeref();
            RuntimeScalar memberName = member.get("_name");
            if (memberName != null && memberName.toString().equals(name)) {
                return members.get(i).getList();
            }
        }
        return scalarUndef.getList();
    }

    /**
     * Get members matching a regex.
     * Usage: @members = $zip->membersMatching('\.txt$');
     */
    public static RuntimeList membersMatching(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String regex = args.get(1).toString();
        RuntimeArray members = getMembers(self);

        RuntimeList result = new RuntimeList();
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
            for (int i = 0; i < members.size(); i++) {
                RuntimeHash member = members.get(i).hashDeref();
                RuntimeScalar memberName = member.get("_name");
                if (memberName != null && pattern.matcher(memberName.toString()).find()) {
                    result.add(members.get(i));
                }
            }
        } catch (Exception e) {
            // Invalid regex, return empty list
        }
        return result;
    }

    /**
     * Add a file to the archive.
     * Usage: $member = $zip->addFile('file.txt');
     *        $member = $zip->addFile('file.txt', 'newname.txt');
     */
    public static RuntimeList addFile(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String filename = args.get(1).toString();
        String memberName = args.size() > 2 ? args.get(2).toString() : filename;

        try {
            Path path = Paths.get(filename);
            if (!Files.exists(path)) {
                return scalarUndef.getList();
            }

            byte[] content = Files.readAllBytes(path);
            long lastModified = Files.getLastModifiedTime(path).toMillis();

            RuntimeHash member = new RuntimeHash();
            member.put("_name", new RuntimeScalar(memberName));
            member.put("_externalFileName", new RuntimeScalar(filename));
            member.put("_contents", new RuntimeScalar(new String(content, StandardCharsets.ISO_8859_1)));
            member.put("_isDirectory", scalarFalse);
            member.put("_uncompressedSize", new RuntimeScalar(content.length));
            member.put("_compressedSize", new RuntimeScalar(content.length));
            member.put("_compressionMethod", new RuntimeScalar(COMPRESSION_DEFLATED));
            member.put("_lastModTime", new RuntimeScalar(lastModified / 1000));
            member.put("_crc32", new RuntimeScalar(computeCRC32(content)));

            RuntimeScalar memberRef = member.createReference();
            ReferenceOperators.bless(memberRef, new RuntimeScalar("Archive::Zip::Member"));

            RuntimeArray members = getMembers(self);
            RuntimeArray.push(members, memberRef);

            return memberRef.getList();

        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }

    /**
     * Add a string as a member.
     * Usage: $member = $zip->addString('content', 'name.txt');
     */
    public static RuntimeList addString(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String content = args.get(1).toString();
        String memberName = args.get(2).toString();

        byte[] contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        RuntimeHash member = new RuntimeHash();
        member.put("_name", new RuntimeScalar(memberName));
        member.put("_externalFileName", new RuntimeScalar(""));
        member.put("_contents", new RuntimeScalar(content));
        member.put("_isDirectory", scalarFalse);
        member.put("_uncompressedSize", new RuntimeScalar(contentBytes.length));
        member.put("_compressedSize", new RuntimeScalar(contentBytes.length));
        member.put("_compressionMethod", new RuntimeScalar(COMPRESSION_DEFLATED));
        member.put("_lastModTime", new RuntimeScalar(System.currentTimeMillis() / 1000));
        member.put("_crc32", new RuntimeScalar(computeCRC32(contentBytes)));

        RuntimeScalar memberRef = member.createReference();
        ReferenceOperators.bless(memberRef, new RuntimeScalar("Archive::Zip::Member"));

        RuntimeArray members = getMembers(self);
        RuntimeArray.push(members, memberRef);

        return memberRef.getList();
    }

    /**
     * Add a directory entry.
     * Usage: $member = $zip->addDirectory('dirname/');
     */
    public static RuntimeList addDirectory(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String dirName = args.get(1).toString();

        // Ensure directory name ends with /
        if (!dirName.endsWith("/")) {
            dirName = dirName + "/";
        }

        RuntimeHash member = new RuntimeHash();
        member.put("_name", new RuntimeScalar(dirName));
        member.put("_externalFileName", new RuntimeScalar(""));
        member.put("_contents", new RuntimeScalar(""));
        member.put("_isDirectory", scalarTrue);
        member.put("_uncompressedSize", scalarZero);
        member.put("_compressedSize", scalarZero);
        member.put("_compressionMethod", new RuntimeScalar(COMPRESSION_STORED));
        member.put("_lastModTime", new RuntimeScalar(System.currentTimeMillis() / 1000));
        member.put("_crc32", scalarZero);

        RuntimeScalar memberRef = member.createReference();
        ReferenceOperators.bless(memberRef, new RuntimeScalar("Archive::Zip::Member"));

        RuntimeArray members = getMembers(self);
        RuntimeArray.push(members, memberRef);

        return memberRef.getList();
    }

    /**
     * Extract a member to a file.
     * Usage: $status = $zip->extractMember('name.txt', 'output.txt');
     */
    public static RuntimeList extractMember(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String memberName = args.get(1).toString();
        String destName = args.size() > 2 ? args.get(2).toString() : memberName;

        try {
            RuntimeArray members = getMembers(self);

            for (int i = 0; i < members.size(); i++) {
                RuntimeHash member = members.get(i).hashDeref();
                RuntimeScalar name = member.get("_name");
                if (name != null && name.toString().equals(memberName)) {
                    RuntimeScalar isDir = member.get("_isDirectory");
                    if (isDir != null && isDir.getBoolean()) {
                        // Create directory
                        Path path = Paths.get(destName);
                        Files.createDirectories(path);
                    } else {
                        // Extract file
                        RuntimeScalar contents = member.get("_contents");
                        if (contents != null) {
                            Path path = Paths.get(destName);
                            // Create parent directories if needed
                            Path parent = path.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            byte[] data = contents.toString().getBytes(StandardCharsets.ISO_8859_1);
                            Files.write(path, data);
                        }
                    }
                    return new RuntimeScalar(AZ_OK).getList();
                }
            }
            return new RuntimeScalar(AZ_ERROR).getList();

        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        }
    }

    /**
     * Extract a member without paths (just filename).
     * Usage: $status = $zip->extractMemberWithoutPaths($member, 'dest/');
     */
    public static RuntimeList extractMemberWithoutPaths(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar memberArg = args.get(1);
        String destDir = args.size() > 2 ? args.get(2).toString() : ".";

        try {
            RuntimeHash member;
            if (RuntimeScalarType.isReference(memberArg)) {
                member = memberArg.hashDeref();
            } else {
                // It's a member name
                RuntimeArray findArgs = new RuntimeArray();
                RuntimeArray.push(findArgs, args.get(0));
                RuntimeArray.push(findArgs, memberArg);
                RuntimeList found = memberNamed(findArgs, ctx);
                if (found.isEmpty() || found.scalar().type == RuntimeScalarType.UNDEF) {
                    return new RuntimeScalar(AZ_ERROR).getList();
                }
                member = found.scalar().hashDeref();
            }

            RuntimeScalar name = member.get("_name");
            if (name == null) {
                return new RuntimeScalar(AZ_ERROR).getList();
            }

            // Get just the filename without path
            String fullName = name.toString();
            String baseName = Paths.get(fullName).getFileName().toString();

            RuntimeScalar isDir = member.get("_isDirectory");
            if (isDir != null && isDir.getBoolean()) {
                // Skip directory entries
                return new RuntimeScalar(AZ_OK).getList();
            }

            RuntimeScalar contents = member.get("_contents");
            if (contents != null) {
                Path destPath = Paths.get(destDir, baseName);
                Path parent = destPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] data = contents.toString().getBytes(StandardCharsets.ISO_8859_1);
                Files.write(destPath, data);
            }

            return new RuntimeScalar(AZ_OK).getList();

        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        }
    }

    /**
     * Extract all members to a directory.
     * Usage: $status = $zip->extractTree('', 'dest/');
     */
    public static RuntimeList extractTree(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(AZ_ERROR).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String root = args.size() > 1 ? args.get(1).toString() : "";
        String dest = args.size() > 2 ? args.get(2).toString() : ".";

        try {
            RuntimeArray members = getMembers(self);

            for (int i = 0; i < members.size(); i++) {
                RuntimeHash member = members.get(i).hashDeref();
                RuntimeScalar name = member.get("_name");
                if (name == null) continue;

                String memberName = name.toString();

                // Filter by root prefix
                if (!root.isEmpty() && !memberName.startsWith(root)) {
                    continue;
                }

                // Remove root prefix for destination
                String destName = memberName;
                if (!root.isEmpty() && memberName.startsWith(root)) {
                    destName = memberName.substring(root.length());
                }

                Path destPath = Paths.get(dest, destName);

                RuntimeScalar isDir = member.get("_isDirectory");
                if (isDir != null && isDir.getBoolean()) {
                    Files.createDirectories(destPath);
                } else {
                    Path parent = destPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    RuntimeScalar contents = member.get("_contents");
                    if (contents != null) {
                        byte[] data = contents.toString().getBytes(StandardCharsets.ISO_8859_1);
                        Files.write(destPath, data);
                    }
                }
            }

            return new RuntimeScalar(AZ_OK).getList();

        } catch (IOException e) {
            return new RuntimeScalar(AZ_IO_ERROR).getList();
        }
    }

    /**
     * Remove a member from the archive.
     * Usage: $removed = $zip->removeMember($member);
     */
    public static RuntimeList removeMember(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar memberArg = args.get(1);
        RuntimeArray members = getMembers(self);

        String targetName;
        if (RuntimeScalarType.isReference(memberArg)) {
            RuntimeHash member = memberArg.hashDeref();
            RuntimeScalar name = member.get("_name");
            targetName = name != null ? name.toString() : "";
        } else {
            targetName = memberArg.toString();
        }

        for (int i = 0; i < members.size(); i++) {
            RuntimeHash member = members.get(i).hashDeref();
            RuntimeScalar name = member.get("_name");
            if (name != null && name.toString().equals(targetName)) {
                RuntimeScalar removed = members.get(i);
                // Remove from array
                RuntimeArray newMembers = new RuntimeArray();
                for (int j = 0; j < members.size(); j++) {
                    if (j != i) {
                        RuntimeArray.push(newMembers, members.get(j));
                    }
                }
                self.put(MEMBERS_KEY, newMembers.createReference());
                return removed.getList();
            }
        }

        return scalarUndef.getList();
    }

    // Member accessor methods

    /**
     * Get member filename.
     */
    public static RuntimeList fileName(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar name = member.get("_name");
        return name != null ? name.getList() : scalarUndef.getList();
    }

    /**
     * Get member contents.
     * Usage: $content = $member->contents();
     *        ($content, $status) = $zip->contents($member);
     * 
     * When called on a zip object with a member argument, returns (content, status) in list context.
     * When called on a member object, returns just the content.
     */
    public static RuntimeList contents(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        
        RuntimeHash self = args.get(0).hashDeref();
        
        // Check if called as $zip->contents($member)
        if (args.size() > 1) {
            // Self is the zip archive, second arg is the member
            RuntimeScalar memberArg = args.get(1);
            RuntimeHash member;
            
            if (RuntimeScalarType.isReference(memberArg)) {
                member = memberArg.hashDeref();
            } else {
                // It's a member name, find it
                String memberName = memberArg.toString();
                RuntimeArray members = getMembers(self);
                member = null;
                for (int i = 0; i < members.size(); i++) {
                    RuntimeHash m = members.get(i).hashDeref();
                    RuntimeScalar name = m.get("_name");
                    if (name != null && name.toString().equals(memberName)) {
                        member = m;
                        break;
                    }
                }
                if (member == null) {
                    // Return (undef, AZ_ERROR) in list context
                    RuntimeList result = new RuntimeList();
                    result.add(scalarUndef);
                    result.add(new RuntimeScalar(AZ_ERROR));
                    return result;
                }
            }
            
            RuntimeScalar contents = member.get("_contents");
            // Return (content, status) in list context
            RuntimeList result = new RuntimeList();
            result.add(contents != null ? contents : scalarUndef);
            result.add(new RuntimeScalar(AZ_OK));
            return result;
        }
        
        // Called as $member->contents()
        RuntimeScalar contents = self.get("_contents");
        return contents != null ? contents.getList() : scalarUndef.getList();
    }

    /**
     * Check if member is a directory.
     */
    public static RuntimeList isDirectory(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar isDir = member.get("_isDirectory");
        return isDir != null ? isDir.getList() : scalarFalse.getList();
    }

    /**
     * Get uncompressed size.
     */
    public static RuntimeList uncompressedSize(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar size = member.get("_uncompressedSize");
        return size != null ? size.getList() : scalarZero.getList();
    }

    /**
     * Get compressed size.
     */
    public static RuntimeList compressedSize(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar size = member.get("_compressedSize");
        return size != null ? size.getList() : scalarZero.getList();
    }

    /**
     * Get compression method.
     */
    public static RuntimeList compressionMethod(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar method = member.get("_compressionMethod");
        return method != null ? method.getList() : scalarZero.getList();
    }

    /**
     * Get last modification time.
     */
    public static RuntimeList lastModTime(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar time = member.get("_lastModTime");
        return time != null ? time.getList() : scalarZero.getList();
    }

    /**
     * Get CRC32.
     */
    public static RuntimeList crc32(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar crc = member.get("_crc32");
        return crc != null ? crc.getList() : scalarZero.getList();
    }

    /**
     * Get external filename.
     */
    public static RuntimeList externalFileName(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar name = member.get("_externalFileName");
        return name != null ? name.getList() : scalarUndef.getList();
    }

    /**
     * Get last modification file date/time.
     */
    public static RuntimeList lastModFileDateTime(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar time = member.get("_lastModFileDateTime");
        return time != null ? time.getList() : scalarZero.getList();
    }

    /**
     * Get version needed to extract.
     */
    public static RuntimeList versionNeededToExtract(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar version = member.get("_versionNeededToExtract");
        return version != null ? version.getList() : new RuntimeScalar(20).getList();
    }

    /**
     * Get bit flag.
     */
    public static RuntimeList bitFlag(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar flag = member.get("_bitFlag");
        return flag != null ? flag.getList() : scalarZero.getList();
    }

    /**
     * Get file comment.
     */
    public static RuntimeList fileComment(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        RuntimeHash member = args.get(0).hashDeref();
        RuntimeScalar comment = member.get("_fileComment");
        return comment != null ? comment.getList() : new RuntimeScalar("").getList();
    }

    // Helper methods

    private static RuntimeArray getMembers(RuntimeHash self) {
        RuntimeScalar membersRef = self.get(MEMBERS_KEY);
        if (membersRef == null || membersRef.type == RuntimeScalarType.UNDEF) {
            RuntimeArray members = new RuntimeArray();
            self.put(MEMBERS_KEY, members.createReference());
            return members;
        }
        return membersRef.arrayDeref();
    }

    private static RuntimeHash createMemberFromEntry(ZipFile zipFile, ZipEntry entry) throws IOException {
        RuntimeHash member = new RuntimeHash();
        member.put("_name", new RuntimeScalar(entry.getName()));
        member.put("_externalFileName", new RuntimeScalar(""));
        member.put("_isDirectory", entry.isDirectory() ? scalarTrue : scalarFalse);
        member.put("_uncompressedSize", new RuntimeScalar(entry.getSize()));
        member.put("_compressedSize", new RuntimeScalar(entry.getCompressedSize()));
        member.put("_compressionMethod", new RuntimeScalar(entry.getMethod()));
        
        // Store Unix timestamp (seconds since epoch) for lastModTime
        long timeMillis = entry.getTime();
        member.put("_lastModTime", new RuntimeScalar(timeMillis / 1000));
        // Store raw MS-DOS format for lastModFileDateTime
        member.put("_lastModFileDateTime", new RuntimeScalar(getRawDosTime(entry)));
        
        member.put("_crc32", new RuntimeScalar(entry.getCrc()));
        
        // Additional fields for ExifTool compatibility
        // versionNeededToExtract: 10 for stored, 20 for deflated (ZIP spec)
        int versionNeeded = entry.getMethod() == ZipEntry.STORED ? 10 : 20;
        member.put("_versionNeededToExtract", new RuntimeScalar(versionNeeded));
        
        // bitFlag is not directly accessible in Java's ZipEntry
        // Default to 0 (no flags set)
        member.put("_bitFlag", new RuntimeScalar(0));
        
        // File comment
        String comment = entry.getComment();
        member.put("_fileComment", comment != null ? new RuntimeScalar(comment) : new RuntimeScalar(""));

        // Read contents if not a directory
        if (!entry.isDirectory()) {
            try (InputStream is = zipFile.getInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                String contents = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
                member.put("_contents", new RuntimeScalar(contents));
            }
        } else {
            member.put("_contents", new RuntimeScalar(""));
        }

        return member;
    }

    private static void writeMemberToZip(ZipOutputStream zos, RuntimeHash member) throws IOException {
        RuntimeScalar name = member.get("_name");
        if (name == null) return;

        ZipEntry entry = new ZipEntry(name.toString());

        RuntimeScalar lastModTime = member.get("_lastModTime");
        if (lastModTime != null) {
            entry.setTime(lastModTime.getLong() * 1000);
        }

        RuntimeScalar isDir = member.get("_isDirectory");
        if (isDir != null && isDir.getBoolean()) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(0);
            entry.setCrc(0);
            zos.putNextEntry(entry);
        } else {
            RuntimeScalar contents = member.get("_contents");
            byte[] data = contents != null
                    ? contents.toString().getBytes(StandardCharsets.ISO_8859_1)
                    : new byte[0];

            RuntimeScalar method = member.get("_compressionMethod");
            if (method != null && method.getInt() == COMPRESSION_STORED) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCrc(computeCRC32(data));
            } else {
                entry.setMethod(ZipEntry.DEFLATED);
            }

            zos.putNextEntry(entry);
            zos.write(data);
        }

        zos.closeEntry();
    }

    private static long computeCRC32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Get the raw DOS time from a ZipEntry.
     * Java's ZipEntry.getTime() converts to UTC, but we need the raw DOS timestamp.
     * We try reflection first; if that fails, fall back to conversion.
     */
    private static long getRawDosTime(ZipEntry entry) {
        try {
            // Try to access the internal 'xdostime' field via reflection
            java.lang.reflect.Field xdostimeField = ZipEntry.class.getDeclaredField("xdostime");
            xdostimeField.setAccessible(true);
            long xdostime = xdostimeField.getLong(entry);
            if (xdostime != -1) {
                // xdostime is a special packed format, extract the DOS time portion
                // In Java's implementation: xdostime contains the 32-bit DOS time in the lower bits
                return xdostime & 0xFFFFFFFFL;
            }
        } catch (Exception e) {
            // Reflection failed, fall back to conversion
        }
        
        // Fallback: convert from Java time (this will have timezone issues)
        return unixTimeToDosFmt(entry.getTime());
    }

    /**
     * Convert Unix time (milliseconds since epoch) to MS-DOS format.
     * MS-DOS format:
     * - Bits 0-4: seconds / 2 (0-29)
     * - Bits 5-10: minutes (0-59)
     * - Bits 11-15: hours (0-23)
     * - Bits 16-20: day (1-31)
     * - Bits 21-24: month (1-12)
     * - Bits 25-31: year - 1980 (0-127)
     * 
     * Note: We use UTC timezone to match the raw DOS timestamp values stored in ZIP files.
     */
    private static long unixTimeToDosFmt(long unixTimeMillis) {
        if (unixTimeMillis < 0) {
            // Return 1980-01-01 00:00:00 for invalid times
            return (0L << 25) | (1L << 21) | (1L << 16) | (0L << 11) | (0L << 5) | 0L;
        }
        
        // Use UTC timezone to avoid local timezone conversion issues
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(unixTimeMillis);
        
        int year = cal.get(java.util.Calendar.YEAR) - 1980;
        int month = cal.get(java.util.Calendar.MONTH) + 1; // Calendar.MONTH is 0-based
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);
        int second = cal.get(java.util.Calendar.SECOND) / 2;
        
        // Clamp year to valid range
        if (year < 0) year = 0;
        if (year > 127) year = 127;
        
        return ((long) year << 25) | ((long) month << 21) | ((long) day << 16) |
               ((long) hour << 11) | ((long) minute << 5) | second;
    }
}
