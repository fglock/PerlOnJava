package org.perlonjava.runtime.runtimetypes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Helper class for handling JAR-embedded Perl library resources.
 * 
 * PerlOnJava bundles Perl modules inside the JAR at /lib/. This class provides
 * utilities for:
 * - Checking if a path refers to a JAR resource
 * - Converting between path formats
 * - Opening JAR resources for reading
 * - File test operations on JAR resources
 * 
 * Path format: "jar:PERL5LIB/Module.pm" maps to JAR resource "/lib/Module.pm"
 */
public class Jar {

    /**
     * Checks if a filename is the JAR virtual directory marker.
     * This appears in @INC as "jar:PERL5LIB".
     */
    public static boolean isJarDirectory(String filename) {
        return GlobalContext.JAR_PERLLIB.equals(filename);
    }

    /**
     * Checks if a filename is a path under the JAR virtual directory.
     * E.g., "jar:PERL5LIB/DBI.pm" or "jar:PERL5LIB/DBI/DBD/SQLite.pm"
     */
    public static boolean isJarPath(String filename) {
        return filename.startsWith(GlobalContext.JAR_PERLLIB + "/");
    }

    /**
     * Checks if a filename refers to any JAR resource (directory or path).
     */
    public static boolean isJarAny(String filename) {
        return isJarDirectory(filename) || isJarPath(filename);
    }

    /**
     * Converts a JAR path to a resource path that can be used with getResource().
     * 
     * @param filename The JAR path (e.g., "jar:PERL5LIB/DBI.pm")
     * @return The resource path (e.g., "/lib/DBI.pm"), or null if not a JAR path
     */
    public static String toResourcePath(String filename) {
        if (isJarPath(filename)) {
            // "jar:PERL5LIB/DBI.pm" -> "/lib/DBI.pm"
            String relativePath = filename.substring(GlobalContext.JAR_PERLLIB.length());
            return "/lib" + relativePath;
        }
        return null;
    }

    /**
     * Gets a URL for a JAR resource.
     * 
     * @param filename The JAR path
     * @return The URL, or null if not found or not a JAR path
     */
    public static URL getResource(String filename) {
        String resourcePath = toResourcePath(filename);
        if (resourcePath == null) {
            return null;
        }
        return RuntimeScalar.class.getResource(resourcePath);
    }

    /**
     * Checks if a JAR resource exists.
     * 
     * @param filename The JAR path
     * @return true if the resource exists
     */
    public static boolean exists(String filename) {
        if (isJarDirectory(filename)) {
            return true;  // The virtual directory always "exists"
        }
        return getResource(filename) != null;
    }

    /**
     * Opens a JAR resource for reading.
     * 
     * @param filename The JAR path
     * @return An InputStream, or null if not found
     * @throws IOException if the resource cannot be opened
     */
    public static InputStream openInputStream(String filename) throws IOException {
        URL resource = getResource(filename);
        if (resource == null) {
            return null;
        }
        return resource.openStream();
    }

    /**
     * Gets the approximate size of a JAR resource.
     * Note: This uses available() which may not return the exact size.
     * 
     * @param filename The JAR path
     * @return The size in bytes, or 0 if not found or on error
     */
    public static long getSize(String filename) {
        try (InputStream is = openInputStream(filename)) {
            if (is == null) {
                return 0;
            }
            return is.available();
        } catch (IOException e) {
            return 0;
        }
    }
}
