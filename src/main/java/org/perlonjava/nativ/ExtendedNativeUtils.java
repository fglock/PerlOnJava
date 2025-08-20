package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import org.perlonjava.runtime.*;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;

/**
 * Extended native operations for missing Perl operators
 */
public class ExtendedNativeUtils extends NativeUtils {

    // Cache for network and user info lookups
    private static final Map<String, RuntimeArray> userInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, RuntimeArray> groupInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, RuntimeArray> hostInfoCache = new ConcurrentHashMap<>();

    // Thread-local iterator state for *ent functions
    private static final ThreadLocal<Iterator<String>> userIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> groupIterator = new ThreadLocal<>();

    // ================== User/Group Information Functions ==================

    /**
     * Get login name of current user
     */
    public static RuntimeScalar getlogin(int ctx, RuntimeBase... args) {
        try {
            String username = System.getProperty("user.name");
            return new RuntimeScalar(username != null ? username : "");
        } catch (Exception e) {
            return new RuntimeScalar("");
        }
    }
    /**
      * Get password entry by username
      */
    public static RuntimeList getpwnam(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeList();
        String username = args[0].toString();

        // In scalar context, return just the username
        if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeScalar(username).getList();
        }

        // Check cache first for full info
        String cacheKey = "user:" + username;
        if (userInfoCache.containsKey(cacheKey)) {
            return userInfoCache.get(cacheKey).getList();
        }

        RuntimeArray result = new RuntimeArray();
        try {
            if (IS_WINDOWS) {
                // Windows implementation
                if (username.equals(System.getProperty("user.name"))) {
                    RuntimeArray.push(result, new RuntimeScalar(username)); // name
                    RuntimeArray.push(result, new RuntimeScalar("x")); // passwd (placeholder)
                    RuntimeArray.push(result, getuid(SCALAR)); // uid
                    RuntimeArray.push(result, getgid(SCALAR)); // gid
                    RuntimeArray.push(result, new RuntimeScalar("")); // quota
                    RuntimeArray.push(result, new RuntimeScalar("")); // comment
                    RuntimeArray.push(result, new RuntimeScalar("")); // gcos
                    RuntimeArray.push(result, new RuntimeScalar(System.getProperty("user.home"))); // dir
                    RuntimeArray.push(result, new RuntimeScalar("cmd.exe")); // shell
                    RuntimeArray.push(result, new RuntimeScalar("")); // expire
                } else {
                    // For other users, provide minimal info
                    RuntimeArray.push(result, new RuntimeScalar(username)); // name
                    RuntimeArray.push(result, new RuntimeScalar("x")); // passwd
                    RuntimeArray.push(result, new RuntimeScalar(username.equals("Administrator") ? 500 : 1001)); // uid
                    RuntimeArray.push(result, new RuntimeScalar(513)); // gid (Users group)
                    RuntimeArray.push(result, new RuntimeScalar(""));
                    RuntimeArray.push(result, new RuntimeScalar(""));
                    RuntimeArray.push(result, new RuntimeScalar(""));
                    RuntimeArray.push(result, new RuntimeScalar("C:\\Users\\" + username)); // dir
                    RuntimeArray.push(result, new RuntimeScalar("cmd.exe")); // shell
                    RuntimeArray.push(result, new RuntimeScalar(""));
                }
            } else {
                // POSIX: Try to read from /etc/passwd
                boolean found = false;
                try (Scanner scanner = new Scanner(new java.io.File("/etc/passwd"))) {
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        String[] parts = line.split(":");
                        if (parts.length >= 7 && parts[0].equals(username)) {
                            RuntimeArray.push(result, new RuntimeScalar(parts[0])); // name
                            RuntimeArray.push(result, new RuntimeScalar(parts[1])); // passwd
                            RuntimeArray.push(result, new RuntimeScalar(Integer.parseInt(parts[2]))); // uid
                            RuntimeArray.push(result, new RuntimeScalar(Integer.parseInt(parts[3]))); // gid
                            RuntimeArray.push(result, new RuntimeScalar("")); // quota (not in /etc/passwd)
                            RuntimeArray.push(result, new RuntimeScalar("")); // comment (not in /etc/passwd)
                            RuntimeArray.push(result, new RuntimeScalar(parts.length > 4 ? parts[4] : "")); // gcos
                            RuntimeArray.push(result, new RuntimeScalar(parts.length > 5 ? parts[5] : "")); // dir
                            RuntimeArray.push(result, new RuntimeScalar(parts.length > 6 ? parts[6] : "")); // shell
                            RuntimeArray.push(result, new RuntimeScalar("")); // expire
                            found = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Fall through to manual entry
                }

                // If not found in /etc/passwd, create entry for known users
                if (!found) {
                    if (username.equals("root")) {
                        RuntimeArray.push(result, new RuntimeScalar("root"));
                        RuntimeArray.push(result, new RuntimeScalar("x"));
                        RuntimeArray.push(result, new RuntimeScalar(0)); // uid
                        RuntimeArray.push(result, new RuntimeScalar(0)); // gid
                        RuntimeArray.push(result, new RuntimeScalar(""));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                        RuntimeArray.push(result, new RuntimeScalar("root"));
                        RuntimeArray.push(result, new RuntimeScalar("/root"));
                        RuntimeArray.push(result, new RuntimeScalar("/bin/bash"));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                    } else if (username.equals(System.getProperty("user.name"))) {
                        RuntimeArray.push(result, new RuntimeScalar(username));
                        RuntimeArray.push(result, new RuntimeScalar("x"));
                        RuntimeArray.push(result, getuid(SCALAR));
                        RuntimeArray.push(result, getgid(SCALAR));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                        RuntimeArray.push(result, new RuntimeScalar(System.getProperty("user.home")));
                        RuntimeArray.push(result, new RuntimeScalar("/bin/bash"));
                        RuntimeArray.push(result, new RuntimeScalar(""));
                    }
                }
            }

            if (result.size() > 0) {
                userInfoCache.put(cacheKey, result);
            }
        } catch (Exception e) {
            // Return empty array on error
        }

        return result.getList();
    }

    /**
      * Get password entry by UID
      */
    public static RuntimeList getpwuid(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeList();

        int uid = args[0].scalar().getInt();
        int currentUid = getuid(SCALAR).getInt();

        if (uid == currentUid) {
            return getpwnam(ctx, new RuntimeScalar(System.getProperty("user.name")));
        }

        return new RuntimeList(); // User not found
    }

    /**
      * Get group entry by name
      */
    public static RuntimeArray getgrnam(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String groupname = args[0].toString();

        String cacheKey = "group:" + groupname;
        if (groupInfoCache.containsKey(cacheKey)) {
            return groupInfoCache.get(cacheKey);
        }

        RuntimeArray result = new RuntimeArray();
        try {
            // Simplified group info - in real implementation would query system
            if (IS_WINDOWS) {
                // Use computer workgroup or domain
                String computerName = System.getenv("COMPUTERNAME");
                if (groupname.equals("Users") || groupname.equals(computerName)) {
                    RuntimeArray.push(result, new RuntimeScalar(groupname)); // name
                    RuntimeArray.push(result, new RuntimeScalar("x")); // passwd
                    RuntimeArray.push(result, getgid(SCALAR)); // gid
                    RuntimeArray members = new RuntimeArray();
                    RuntimeArray.push(members, new RuntimeScalar(System.getProperty("user.name")));
                    RuntimeArray.push(result, members); // members
                }
            } else {
                // POSIX - simplified
                if (groupname.equals("users") || groupname.equals(System.getProperty("user.name"))) {
                    RuntimeArray.push(result, new RuntimeScalar(groupname));
                    RuntimeArray.push(result, new RuntimeScalar("x"));
                    RuntimeArray.push(result, getgid(SCALAR));
                    RuntimeArray members = new RuntimeArray();
                    RuntimeArray.push(members, new RuntimeScalar(System.getProperty("user.name")));
                    RuntimeArray.push(result, members);
                }
            }

            groupInfoCache.put(cacheKey, result);
        } catch (Exception e) {
            // Return empty on error
        }

        return result;
    }

    /**
     * Get group entry by GID
     */
    public static RuntimeArray getgrgid(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();

        int gid = args[0].scalar().getInt();
        int currentGid = getgid(ctx).getInt();

        if (gid == currentGid) {
            String groupName = IS_WINDOWS ? "Users" : "users";
            return getgrnam(ctx, new RuntimeScalar(groupName));
        }

        return new RuntimeArray();
    }

    // Iterator functions for user/group entries
    public static RuntimeList getpwent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = userIterator.get();
        if (iterator == null) {
            List<String> users = getSystemUsers();
            iterator = users.iterator();
            userIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            return getpwnam(ctx, new RuntimeScalar(iterator.next()));
        }

        return new RuntimeList(); // End of entries
    }

    public static RuntimeArray getgrent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = groupIterator.get();
        if (iterator == null) {
            List<String> groups = getSystemGroups();
            iterator = groups.iterator();
            groupIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            return getgrnam(ctx, new RuntimeScalar(iterator.next()));
        }

        return new RuntimeArray();
    }

    public static RuntimeScalar setpwent(int ctx, RuntimeBase... args) {
        userIterator.remove(); // Clear this thread's iterator
        userInfoCache.clear();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar setgrent(int ctx, RuntimeBase... args) {
        groupIterator.remove(); // Clear this thread's iterator
        groupInfoCache.clear();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endpwent(int ctx, RuntimeBase... args) {
        userIterator.remove(); // Clear this thread's iterator
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endgrent(int ctx, RuntimeBase... args) {
        groupIterator.remove(); // Clear this thread's iterator
        return new RuntimeScalar(1);
    }

    // ================== Network Information Functions ==================

    /**
     * Get host information by name
     */
    public static RuntimeArray gethostbyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String hostname = args[0].toString();

        String cacheKey = "host:" + hostname;
        if (hostInfoCache.containsKey(cacheKey)) {
            return hostInfoCache.get(cacheKey);
        }

        RuntimeArray result = new RuntimeArray();
        try {
            InetAddress addr = InetAddress.getByName(hostname);

            RuntimeArray.push(result, new RuntimeScalar(addr.getHostName())); // name
            RuntimeArray aliases = new RuntimeArray(); // aliases (empty for now)
            RuntimeArray.push(result, aliases);
            RuntimeArray.push(result, new RuntimeScalar(2)); // addrtype (AF_INET)
            RuntimeArray.push(result, new RuntimeScalar(4)); // length (IPv4 = 4 bytes)

            RuntimeArray addresses = new RuntimeArray();
            RuntimeArray.push(addresses, new RuntimeScalar(addr.getAddress())); // packed address
            RuntimeArray.push(result, addresses);

            hostInfoCache.put(cacheKey, result);
        } catch (Exception e) {
            // Return empty array on error
        }

        return result;
    }

    /**
     * Get host information by address
     */
    public static RuntimeArray gethostbyaddr(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        try {
            byte[] addr;
            if (args[0] instanceof RuntimeScalar) {
                String addrStr = args[0].toString();
                if (addrStr.length() == 4) {
                    // Packed address
                    addr = addrStr.getBytes(StandardCharsets.ISO_8859_1);
                } else {
                    // String IP address
                    String[] parts = addrStr.split("\\.");
                    addr = new byte[4];
                    for (int i = 0; i < 4 && i < parts.length; i++) {
                        addr[i] = (byte) Integer.parseInt(parts[i]);
                    }
                }
            } else {
                return new RuntimeArray();
            }

            InetAddress inetAddr = InetAddress.getByAddress(addr);

            RuntimeArray result = new RuntimeArray();
            RuntimeArray.push(result, new RuntimeScalar(inetAddr.getHostName()));
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(2)); // addrtype
            RuntimeArray.push(result, new RuntimeScalar(4)); // length

            RuntimeArray addresses = new RuntimeArray();
            RuntimeArray.push(addresses, new RuntimeScalar(new String(addr, StandardCharsets.ISO_8859_1)));
            RuntimeArray.push(result, addresses);

            return result;
        } catch (Exception e) {
            return new RuntimeArray();
        }
    }

    /**
     * Get service information by name and protocol
     */
    public static RuntimeArray getservbyname(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        String service = args[0].toString();
        String protocol = args[1].toString();

        // Common services mapping
        Map<String, Integer> commonPorts = Map.of(
                "http", 80, "https", 443, "ftp", 21, "ssh", 22,
                "telnet", 23, "smtp", 25, "dns", 53, "pop3", 110,
                "imap", 143, "snmp", 161
        );

        RuntimeArray result = new RuntimeArray();
        Integer port = commonPorts.get(service.toLowerCase());
        if (port != null) {
            RuntimeArray.push(result, new RuntimeScalar(service)); // name
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(port)); // port
            RuntimeArray.push(result, new RuntimeScalar(protocol)); // proto
        }

        return result;
    }

    /**
     * Get service information by port and protocol
     */
    public static RuntimeArray getservbyport(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        int port = args[0].scalar().getInt();
        String protocol = args[1].toString();

        // Reverse lookup of common ports
        Map<Integer, String> commonServices = Map.of(
                80, "http", 443, "https", 21, "ftp", 22, "ssh",
                23, "telnet", 25, "smtp", 53, "dns", 110, "pop3",
                143, "imap", 161, "snmp"
        );

        RuntimeArray result = new RuntimeArray();
        String service = commonServices.get(port);
        if (service != null) {
            RuntimeArray.push(result, new RuntimeScalar(service)); // name
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(port)); // port
            RuntimeArray.push(result, new RuntimeScalar(protocol)); // proto
        }

        return result;
    }

    /**
     * Get protocol information by name
     */
    public static RuntimeArray getprotobyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String protocol = args[0].toString().toLowerCase();

        Map<String, Integer> protocols = Map.of(
                "tcp", 6, "udp", 17, "icmp", 1, "ip", 0
        );

        RuntimeArray result = new RuntimeArray();
        Integer protoNum = protocols.get(protocol);
        if (protoNum != null) {
            RuntimeArray.push(result, new RuntimeScalar(protocol)); // name
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(protoNum)); // proto number
        }

        return result;
    }

    /**
     * Get protocol information by number
     */
    public static RuntimeArray getprotobynumber(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        int protoNum = args[0].scalar().getInt();

        Map<Integer, String> protocols = Map.of(
                6, "tcp", 17, "udp", 1, "icmp", 0, "ip"
        );

        RuntimeArray result = new RuntimeArray();
        String protocol = protocols.get(protoNum);
        if (protocol != null) {
            RuntimeArray.push(result, new RuntimeScalar(protocol)); // name
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(protoNum)); // proto number
        }

        return result;
    }

    /**
     * Get list of system users (cross-platform)
     */
    private static List<String> getSystemUsers() {
        List<String> users = new ArrayList<>();

        try {
            if (IS_WINDOWS) {
                // Windows: Use 'net user' command
                Process proc = Runtime.getRuntime().exec(new String[]{"net", "user"});
                try (Scanner scanner = new Scanner(proc.getInputStream())) {
                    boolean inUserList = false;
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();

                        // Skip header lines
                        if (line.contains("User accounts for")) {
                            inUserList = true;
                            continue;
                        }
                        if (!inUserList || line.isEmpty() || line.startsWith("-")) {
                            continue;
                        }
                        if (line.startsWith("The command completed")) {
                            break;
                        }

                        // Parse user names (they're space-separated)
                        String[] usernames = line.split("\\s+");
                        for (String username : usernames) {
                            if (!username.isEmpty() && !username.equals("Administrator") &&
                                    !username.equals("Guest") && !username.equals("DefaultAccount")) {
                                users.add(username);
                            }
                        }
                    }
                }
            } else {
                // POSIX: Read /etc/passwd
                try (Scanner scanner = new Scanner(new java.io.File("/etc/passwd"))) {
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        if (line.startsWith("#") || line.trim().isEmpty()) {
                            continue;
                        }

                        String[] parts = line.split(":");
                        if (parts.length >= 3) {
                            String username = parts[0];
                            int uid = Integer.parseInt(parts[2]);

                            // Include system users like root (uid 0) and regular users
                            if (uid <= 65534) { // Exclude nobody/nogroup
                                users.add(username);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Fallback if can't read /etc/passwd
                    users.add("root");
                    users.add(System.getProperty("user.name"));
                }
            }
        } catch (Exception e) {
            // Fallback to current user only
            users.add(System.getProperty("user.name"));
        }

        // Ensure we always have at least the current user
        String currentUser = System.getProperty("user.name");
        if (!users.contains(currentUser)) {
            users.add(currentUser);
        }

        return users;
    }

    /**
     * Get list of system groups (cross-platform)
     */
    private static List<String> getSystemGroups() {
        List<String> groups = new ArrayList<>();

        try {
            if (IS_WINDOWS) {
                // Windows: Common built-in groups
                groups.addAll(Arrays.asList("Users", "Administrators", "Guests", "Power Users"));
            } else {
                // POSIX: Read /etc/group
                try (Scanner scanner = new Scanner(new java.io.File("/etc/group"))) {
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        if (line.startsWith("#") || line.trim().isEmpty()) {
                            continue;
                        }

                        String[] parts = line.split(":");
                        if (parts.length >= 3) {
                            String groupname = parts[0];
                            int gid = Integer.parseInt(parts[2]);

                            // Include system and user groups
                            if (gid <= 65534) {
                                groups.add(groupname);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Fallback
                    groups.addAll(Arrays.asList("root", "users", "wheel"));
                }
            }
        } catch (Exception e) {
            // Fallback
            groups.add(IS_WINDOWS ? "Users" : "users");
        }

        return groups;
    }
}
