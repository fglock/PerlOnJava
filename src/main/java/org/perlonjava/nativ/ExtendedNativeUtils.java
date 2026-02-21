package org.perlonjava.nativ;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;

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
    private static final ThreadLocal<Iterator<String>> hostIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> netIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> protoIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> servIterator = new ThreadLocal<>();

    // System V IPC structures simulation
    private static final Map<Integer, RuntimeArray> messageQueues = new ConcurrentHashMap<>();
    private static final Map<Integer, RuntimeArray> semaphores = new ConcurrentHashMap<>();
    private static final Map<Integer, RuntimeArray> sharedMemory = new ConcurrentHashMap<>();
    private static int nextIpcId = 1000;

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
            RuntimeArray cached = hostInfoCache.get(cacheKey);
            // In scalar context, return the packed IP address (4th element)
            if (ctx == SCALAR && cached.size() >= 5) {
                // The packed IP address is now directly at index 4
                RuntimeBase packedAddress = cached.get(4);
                RuntimeArray scalarResult = new RuntimeArray();
                RuntimeArray.push(scalarResult, packedAddress);
                return scalarResult;
            }
            return cached;
        }

        RuntimeArray result = new RuntimeArray();
        try {
            InetAddress addr = InetAddress.getByName(hostname);

            RuntimeArray.push(result, new RuntimeScalar(addr.getHostName())); // name
            RuntimeArray aliases = new RuntimeArray(); // aliases (empty for now)
            RuntimeArray.push(result, aliases);
            RuntimeArray.push(result, new RuntimeScalar(2)); // addrtype (AF_INET)
            RuntimeArray.push(result, new RuntimeScalar(4)); // length (IPv4 = 4 bytes)

            // Add the packed IP addresses as individual elements (not as an array)
            RuntimeScalar packedAddress = new RuntimeScalar(addr.getAddress());
            RuntimeArray.push(result, packedAddress); // packed address

            hostInfoCache.put(cacheKey, result);

            // In scalar context, return the packed IP address
            if (ctx == SCALAR) {
                RuntimeArray scalarResult = new RuntimeArray();
                RuntimeArray.push(scalarResult, packedAddress);
                return scalarResult;
            }
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
                StringParser.assertNoWideCharacters(addrStr, "gethostbyaddr");
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
        } catch (PerlCompilerException e) {
            throw e;
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

    // ================== System V IPC Functions ==================

    /**
     * Message queue control operations
     */
    public static RuntimeScalar msgctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        int cmd = args[1].scalar().getInt();
        RuntimeBase buf = args[2];

        // Simulate basic msgctl operations
        switch (cmd) {
            case 0: // IPC_STAT
                if (messageQueues.containsKey(msqid)) {
                    return new RuntimeScalar(0); // Success
                }
                break;
            case 1: // IPC_SET
                if (messageQueues.containsKey(msqid)) {
                    return new RuntimeScalar(0); // Success
                }
                break;
            case 2: // IPC_RMID
                if (messageQueues.remove(msqid) != null) {
                    return new RuntimeScalar(0); // Success
                }
                break;
        }
        return new RuntimeScalar(-1); // Error
    }

    /**
     * Get message queue identifier
     */
    public static RuntimeScalar msgget(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int msgflg = args[1].scalar().getInt();

        // Create or get existing message queue
        int msqid = nextIpcId++;
        RuntimeArray msgQueue = new RuntimeArray();
        messageQueues.put(msqid, msgQueue);

        return new RuntimeScalar(msqid);
    }

    /**
     * Receive message from queue
     */
    public static RuntimeScalar msgrcv(int ctx, RuntimeBase... args) {
        if (args.length < 5) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        RuntimeBase msg = args[1];
        int msgsz = args[2].scalar().getInt();
        int msgtyp = args[3].scalar().getInt();
        int msgflg = args[4].scalar().getInt();

        RuntimeArray msgQueue = messageQueues.get(msqid);
        if (msgQueue == null || msgQueue.size() == 0) {
            return new RuntimeScalar(-1); // No messages
        }

        // Simulate receiving first message
        RuntimeBase message = msgQueue.get(0);
        msgQueue.elements.remove(0);

        return new RuntimeScalar(message.toString().length());
    }

    /**
     * Send message to queue
     */
    public static RuntimeScalar msgsnd(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        RuntimeBase msgp = args[1];
        int msgsz = args[2].scalar().getInt();

        RuntimeArray msgQueue = messageQueues.get(msqid);
        if (msgQueue == null) {
            return new RuntimeScalar(-1); // Queue doesn't exist
        }

        // Add message to queue
        RuntimeArray.push(msgQueue, msgp);
        return new RuntimeScalar(0); // Success
    }

    /**
     * Semaphore control operations
     */
    public static RuntimeScalar semctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int semid = args[0].scalar().getInt();
        int semnum = args[1].scalar().getInt();
        int cmd = args[2].scalar().getInt();

        RuntimeArray semArray = semaphores.get(semid);
        if (semArray == null) {
            return new RuntimeScalar(-1); // Semaphore doesn't exist
        }

        // Simulate basic semctl operations
        switch (cmd) {
            case 0: // GETVAL
                if (semnum < semArray.size()) {
                    return semArray.get(semnum).scalar();
                }
                break;
            case 1: // SETVAL
                if (args.length > 3 && semnum < semArray.size()) {
                    semArray.elements.set(semnum, args[3].scalar());
                    return new RuntimeScalar(0);
                }
                break;
            case 2: // IPC_RMID
                if (semaphores.remove(semid) != null) {
                    return new RuntimeScalar(0);
                }
                break;
        }
        return new RuntimeScalar(-1);
    }

    /**
     * Get semaphore identifier
     */
    public static RuntimeScalar semget(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int nsems = args[1].scalar().getInt();
        int semflg = args[2].scalar().getInt();

        // Create semaphore array
        int semid = nextIpcId++;
        RuntimeArray semArray = new RuntimeArray();
        for (int i = 0; i < nsems; i++) {
            RuntimeArray.push(semArray, new RuntimeScalar(0));
        }
        semaphores.put(semid, semArray);

        return new RuntimeScalar(semid);
    }

    /**
     * Semaphore operations
     */
    public static RuntimeScalar semop(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeScalar(-1);

        int semid = args[0].scalar().getInt();
        RuntimeBase sops = args[1];

        RuntimeArray semArray = semaphores.get(semid);
        if (semArray == null) {
            return new RuntimeScalar(-1); // Semaphore doesn't exist
        }

        // Simulate semaphore operation (simplified)
        return new RuntimeScalar(0); // Success
    }

    /**
     * Shared memory control operations
     */
    public static RuntimeScalar shmctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int shmid = args[0].scalar().getInt();
        int cmd = args[1].scalar().getInt();
        RuntimeBase buf = args[2];

        RuntimeArray shmSeg = sharedMemory.get(shmid);

        switch (cmd) {
            case 0: // IPC_STAT
                if (shmSeg != null) {
                    return new RuntimeScalar(0);
                }
                break;
            case 1: // IPC_SET
                if (shmSeg != null) {
                    return new RuntimeScalar(0);
                }
                break;
            case 2: // IPC_RMID
                if (sharedMemory.remove(shmid) != null) {
                    return new RuntimeScalar(0);
                }
                break;
        }
        return new RuntimeScalar(-1);
    }

    /**
     * Get shared memory identifier
     */
    public static RuntimeScalar shmget(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int size = args[1].scalar().getInt();
        int shmflg = args[2].scalar().getInt();

        // Create shared memory segment
        int shmid = nextIpcId++;
        RuntimeArray shmSeg = new RuntimeArray();
        // Initialize with zeros
        for (int i = 0; i < size; i++) {
            RuntimeArray.push(shmSeg, new RuntimeScalar(0));
        }
        sharedMemory.put(shmid, shmSeg);

        return new RuntimeScalar(shmid);
    }

    /**
     * Read from shared memory
     */
    public static RuntimeScalar shmread(int ctx, RuntimeBase... args) {
        if (args.length < 4) return new RuntimeScalar(-1);

        int shmid = args[0].scalar().getInt();
        RuntimeBase var = args[1];
        int pos = args[2].scalar().getInt();
        int size = args[3].scalar().getInt();

        RuntimeArray shmSeg = sharedMemory.get(shmid);
        if (shmSeg == null || pos < 0 || pos >= shmSeg.size()) {
            return new RuntimeScalar(-1);
        }

        // Read data from shared memory segment
        StringBuilder data = new StringBuilder();
        for (int i = pos; i < Math.min(pos + size, shmSeg.size()); i++) {
            data.append((char) shmSeg.get(i).scalar().getInt());
        }

        // Set the variable to the read data
        if (var instanceof RuntimeScalar) {
            ((RuntimeScalar) var).set(data.toString());
        }

        return new RuntimeScalar(data.length());
    }

    /**
     * Write to shared memory
     */
    public static RuntimeScalar shmwrite(int ctx, RuntimeBase... args) {
        if (args.length < 4) return new RuntimeScalar(-1);

        int shmid = args[0].scalar().getInt();
        String string = args[1].toString();
        int pos = args[2].scalar().getInt();
        int size = args[3].scalar().getInt();

        RuntimeArray shmSeg = sharedMemory.get(shmid);
        if (shmSeg == null || pos < 0) {
            return new RuntimeScalar(-1);
        }

        // Write data to shared memory segment
        byte[] bytes = string.getBytes();
        int writeSize = Math.min(size, bytes.length);

        // Extend segment if necessary
        while (shmSeg.size() <= pos + writeSize) {
            RuntimeArray.push(shmSeg, new RuntimeScalar(0));
        }

        for (int i = 0; i < writeSize; i++) {
            shmSeg.elements.set(pos + i, new RuntimeScalar(bytes[i] & 0xFF));
        }

        return new RuntimeScalar(writeSize);
    }

    // ================== Network Enumeration Functions ==================

    /**
     * End host entries enumeration
     */
    public static RuntimeScalar endhostent(int ctx, RuntimeBase... args) {
        hostIterator.remove();
        return new RuntimeScalar(1);
    }

    /**
     * End network entries enumeration
     */
    public static RuntimeScalar endnetent(int ctx, RuntimeBase... args) {
        netIterator.remove();
        return new RuntimeScalar(1);
    }

    /**
     * End protocol entries enumeration
     */
    public static RuntimeScalar endprotoent(int ctx, RuntimeBase... args) {
        protoIterator.remove();
        return new RuntimeScalar(1);
    }

    /**
     * End service entries enumeration
     */
    public static RuntimeScalar endservent(int ctx, RuntimeBase... args) {
        servIterator.remove();
        return new RuntimeScalar(1);
    }

    /**
     * Get next host entry
     */
    public static RuntimeArray gethostent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = hostIterator.get();
        if (iterator == null) {
            List<String> hosts = getSystemHosts();
            iterator = hosts.iterator();
            hostIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            String hostname = iterator.next();
            return gethostbyname(ctx, new RuntimeScalar(hostname));
        }

        return new RuntimeArray(); // End of entries
    }

    /**
     * Get network by address
     */
    public static RuntimeArray getnetbyaddr(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        String addr = args[0].toString();
        int addrtype = args[1].scalar().getInt();

        // Simplified network lookup
        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar("loopback")); // name
        RuntimeArray.push(result, new RuntimeArray()); // aliases
        RuntimeArray.push(result, new RuntimeScalar(addrtype)); // addrtype
        RuntimeArray.push(result, new RuntimeScalar("127.0.0.1")); // net

        return result;
    }

    /**
     * Get network by name
     */
    public static RuntimeArray getnetbyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();

        String name = args[0].toString();

        // Simplified network lookup
        RuntimeArray result = new RuntimeArray();
        if (name.equals("loopback") || name.equals("localhost")) {
            RuntimeArray.push(result, new RuntimeScalar(name)); // name
            RuntimeArray.push(result, new RuntimeArray()); // aliases
            RuntimeArray.push(result, new RuntimeScalar(2)); // addrtype (AF_INET)
            RuntimeArray.push(result, new RuntimeScalar("127.0.0.1")); // net
        }

        return result;
    }

    /**
     * Get next network entry
     */
    public static RuntimeArray getnetent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = netIterator.get();
        if (iterator == null) {
            List<String> networks = Arrays.asList("loopback", "localhost");
            iterator = networks.iterator();
            netIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            String netname = iterator.next();
            return getnetbyname(ctx, new RuntimeScalar(netname));
        }

        return new RuntimeArray(); // End of entries
    }

    /**
     * Get next protocol entry
     */
    public static RuntimeArray getprotoent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = protoIterator.get();
        if (iterator == null) {
            List<String> protocols = Arrays.asList("tcp", "udp", "icmp", "ip");
            iterator = protocols.iterator();
            protoIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            String protoname = iterator.next();
            return getprotobyname(ctx, new RuntimeScalar(protoname));
        }

        return new RuntimeArray(); // End of entries
    }

    /**
     * Get next service entry
     */
    public static RuntimeArray getservent(int ctx, RuntimeBase... args) {
        Iterator<String> iterator = servIterator.get();
        if (iterator == null) {
            List<String> services = Arrays.asList("http", "https", "ftp", "ssh", "telnet", "smtp", "dns", "pop3", "imap", "snmp");
            iterator = services.iterator();
            servIterator.set(iterator);
        }

        if (iterator.hasNext()) {
            String servicename = iterator.next();
            return getservbyname(ctx, new RuntimeScalar(servicename), new RuntimeScalar("tcp"));
        }

        return new RuntimeArray(); // End of entries
    }

    /**
     * Set host entries enumeration
     */
    public static RuntimeScalar sethostent(int ctx, RuntimeBase... args) {
        hostIterator.remove(); // Reset iterator
        return new RuntimeScalar(1);
    }

    /**
     * Set network entries enumeration
     */
    public static RuntimeScalar setnetent(int ctx, RuntimeBase... args) {
        netIterator.remove(); // Reset iterator
        return new RuntimeScalar(1);
    }

    /**
     * Set protocol entries enumeration
     */
    public static RuntimeScalar setprotoent(int ctx, RuntimeBase... args) {
        protoIterator.remove(); // Reset iterator
        return new RuntimeScalar(1);
    }

    /**
     * Set service entries enumeration
     */
    public static RuntimeScalar setservent(int ctx, RuntimeBase... args) {
        servIterator.remove(); // Reset iterator
        return new RuntimeScalar(1);
    }

    /**
     * Get list of system hosts (simplified)
     */
    private static List<String> getSystemHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("localhost");
        hosts.add("127.0.0.1");

        try {
            // Add local hostname
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            if (!hosts.contains(hostname)) {
                hosts.add(hostname);
            }
        } catch (Exception e) {
            // Ignore
        }

        return hosts;
    }
}
