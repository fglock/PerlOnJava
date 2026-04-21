package org.perlonjava.runtime.nativ;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;
import org.perlonjava.runtime.runtimetypes.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;

public class ExtendedNativeUtils extends NativeUtils {

    private static final Map<String, RuntimeArray> userInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, RuntimeArray> groupInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, RuntimeArray> hostInfoCache = new ConcurrentHashMap<>();

    private static final ThreadLocal<Iterator<String>> userIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> groupIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> hostIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> netIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> protoIterator = new ThreadLocal<>();
    private static final ThreadLocal<Iterator<String>> servIterator = new ThreadLocal<>();

    private static final Map<Integer, RuntimeArray> messageQueues = new ConcurrentHashMap<>();
    private static final Map<Integer, RuntimeArray> semaphores = new ConcurrentHashMap<>();
    private static final Map<Integer, RuntimeArray> sharedMemory = new ConcurrentHashMap<>();
    private static int nextIpcId = 1000;

    // ================== User/Group Information Functions ==================

    public static RuntimeScalar getlogin(int ctx, RuntimeBase... args) {
        try {
            String username = System.getProperty("user.name");
            return new RuntimeScalar(username != null ? username : "");
        } catch (Exception e) {
            return new RuntimeScalar("");
        }
    }

    private static RuntimeList passwdToList(FFMPosixInterface.PasswdEntry pw) {
        if (pw == null) return new RuntimeList();
        RuntimeArray result = new RuntimeArray();
        String name = pw.name();
        String passwd = pw.passwd();
        int uid = pw.uid();
        int gid = pw.gid();
        if (IS_MAC) {
            long change = pw.change();
            String gecos = pw.gecos();
            String dir = pw.dir();
            String shell = pw.shell();
            long expire = pw.expire();
            RuntimeArray.push(result, new RuntimeScalar(name));
            RuntimeArray.push(result, new RuntimeScalar(passwd));
            RuntimeArray.push(result, new RuntimeScalar(uid));
            RuntimeArray.push(result, new RuntimeScalar(gid));
            RuntimeArray.push(result, new RuntimeScalar(change));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(gecos));
            RuntimeArray.push(result, new RuntimeScalar(dir));
            RuntimeArray.push(result, new RuntimeScalar(shell));
            RuntimeArray.push(result, new RuntimeScalar(expire));
        } else {
            String gecos = pw.gecos();
            String dir = pw.dir();
            String shell = pw.shell();
            RuntimeArray.push(result, new RuntimeScalar(name));
            RuntimeArray.push(result, new RuntimeScalar(passwd));
            RuntimeArray.push(result, new RuntimeScalar(uid));
            RuntimeArray.push(result, new RuntimeScalar(gid));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(gecos));
            RuntimeArray.push(result, new RuntimeScalar(dir));
            RuntimeArray.push(result, new RuntimeScalar(shell));
            RuntimeArray.push(result, new RuntimeScalar(""));
        }
        return result.getList();
    }

    private static RuntimeList nativeGetpwnam(String username) {
        FFMPosixInterface.PasswdEntry pw = FFMPosix.get().getpwnam(username);
        return passwdToList(pw);
    }

    private static RuntimeList nativeGetpwuid(int uid) {
        FFMPosixInterface.PasswdEntry pw = FFMPosix.get().getpwuid(uid);
        return passwdToList(pw);
    }

    public static RuntimeList getpwnam(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeList();
        String username = args[0].toString();
        if (IS_WINDOWS) {
            if (ctx == RuntimeContextType.SCALAR) return new RuntimeList();
            return windowsGetpwnam(username);
        }
        try {
            if (ctx == RuntimeContextType.SCALAR) {
                FFMPosixInterface.PasswdEntry pw = FFMPosix.get().getpwnam(username);
                if (pw != null) return new RuntimeScalar(pw.uid()).getList();
                return new RuntimeList();
            }
            return nativeGetpwnam(username);
        } catch (Exception e) {
            return new RuntimeList();
        }
    }

    public static RuntimeList getpwuid(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeList();
        int uid = args[0].scalar().getInt();
        if (IS_WINDOWS) {
            if (ctx == RuntimeContextType.SCALAR) return new RuntimeList();
            return windowsGetpwuid(uid);
        }
        try {
            if (ctx == RuntimeContextType.SCALAR) {
                FFMPosixInterface.PasswdEntry pw = FFMPosix.get().getpwuid(uid);
                if (pw != null) return new RuntimeScalar(pw.name()).getList();
                return new RuntimeList();
            }
            return nativeGetpwuid(uid);
        } catch (Exception e) {
            return new RuntimeList();
        }
    }

    private static RuntimeList windowsGetpwnam(String username) {
        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar(username));
        RuntimeArray.push(result, new RuntimeScalar("x"));
        if (username.equals(System.getProperty("user.name"))) {
            RuntimeArray.push(result, getuid(SCALAR));
            RuntimeArray.push(result, getgid(SCALAR));
        } else {
            RuntimeArray.push(result, new RuntimeScalar(username.equals("Administrator") ? 500 : 1001));
            RuntimeArray.push(result, new RuntimeScalar(513));
        }
        RuntimeArray.push(result, new RuntimeScalar(""));
        RuntimeArray.push(result, new RuntimeScalar(""));
        RuntimeArray.push(result, new RuntimeScalar(""));
        String home = username.equals(System.getProperty("user.name"))
                ? System.getProperty("user.home") : "C:\\Users\\" + username;
        RuntimeArray.push(result, new RuntimeScalar(home));
        RuntimeArray.push(result, new RuntimeScalar("cmd.exe"));
        RuntimeArray.push(result, new RuntimeScalar(""));
        return result.getList();
    }

    private static RuntimeList windowsGetpwuid(int uid) {
        int currentUid = getuid(SCALAR).getInt();
        if (uid == currentUid) return windowsGetpwnam(System.getProperty("user.name"));
        return new RuntimeList();
    }

    public static RuntimeArray getgrnam(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String groupname = args[0].toString();

        String cacheKey = "group:" + groupname;
        if (groupInfoCache.containsKey(cacheKey)) {
            return groupInfoCache.get(cacheKey);
        }

        RuntimeArray result = new RuntimeArray();
        try {
            if (IS_WINDOWS) {
                String computerName = System.getenv("COMPUTERNAME");
                if (groupname.equals("Users") || groupname.equals(computerName)) {
                    RuntimeArray.push(result, new RuntimeScalar(groupname));
                    RuntimeArray.push(result, new RuntimeScalar("x"));
                    RuntimeArray.push(result, getgid(SCALAR));
                    RuntimeArray members = new RuntimeArray();
                    RuntimeArray.push(members, new RuntimeScalar(System.getProperty("user.name")));
                    RuntimeArray.push(result, members);
                }
            } else {
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
        }

        return result;
    }

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

    public static RuntimeList getpwent(int ctx, RuntimeBase... args) {
        if (IS_WINDOWS) return new RuntimeList();
        try {
            FFMPosixInterface.PasswdEntry pw = FFMPosix.get().getpwent();
            if (pw == null) return new RuntimeList();
            if (ctx == RuntimeContextType.SCALAR) {
                return new RuntimeScalar(pw.name()).getList();
            }
            return passwdToList(pw);
        } catch (Exception e) {
            return new RuntimeList();
        }
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
        if (!IS_WINDOWS) {
            try {
                FFMPosix.get().setpwent();
            } catch (Exception e) {
            }
        }
        userIterator.remove();
        userInfoCache.clear();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar setgrent(int ctx, RuntimeBase... args) {
        groupIterator.remove();
        groupInfoCache.clear();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endpwent(int ctx, RuntimeBase... args) {
        if (!IS_WINDOWS) {
            try {
                FFMPosix.get().endpwent();
            } catch (Exception e) {
            }
        }
        userIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endgrent(int ctx, RuntimeBase... args) {
        groupIterator.remove();
        return new RuntimeScalar(1);
    }

    // ================== Network Information Functions ==================

    public static RuntimeArray gethostbyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String hostname = args[0].toString();

        String cacheKey = "host:" + hostname;
        if (hostInfoCache.containsKey(cacheKey)) {
            RuntimeArray cached = hostInfoCache.get(cacheKey);
            if (ctx == SCALAR && cached.size() >= 5) {
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

            RuntimeArray.push(result, new RuntimeScalar(addr.getHostName()));
            // Aliases field: must be a scalar (empty string), not an empty array
            // which would flatten to zero elements and shift subsequent fields
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(2));
            RuntimeArray.push(result, new RuntimeScalar(4));

            RuntimeScalar packedAddress = new RuntimeScalar(addr.getAddress());
            RuntimeArray.push(result, packedAddress);

            hostInfoCache.put(cacheKey, result);

            if (ctx == SCALAR) {
                RuntimeArray scalarResult = new RuntimeArray();
                RuntimeArray.push(scalarResult, packedAddress);
                return scalarResult;
            }
        } catch (Exception e) {
        }

        return result;
    }

    public static RuntimeArray gethostbyaddr(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        try {
            byte[] addr;
            if (args[0] instanceof RuntimeScalar) {
                String addrStr = args[0].toString();
                StringParser.assertNoWideCharacters(addrStr, "gethostbyaddr");
                if (addrStr.length() == 4) {
                    addr = addrStr.getBytes(StandardCharsets.ISO_8859_1);
                } else {
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
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(2));
            RuntimeArray.push(result, new RuntimeScalar(4));

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

    public static RuntimeArray getservbyname(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        String service = args[0].toString();
        String protocol = args[1].toString();

        Map<String, Integer> commonPorts = Map.of(
                "http", 80, "https", 443, "ftp", 21, "ssh", 22,
                "telnet", 23, "smtp", 25, "dns", 53, "pop3", 110,
                "imap", 143, "snmp", 161
        );

        RuntimeArray result = new RuntimeArray();
        Integer port = commonPorts.get(service.toLowerCase());
        if (port != null) {
            RuntimeArray.push(result, new RuntimeScalar(service));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(port));
            RuntimeArray.push(result, new RuntimeScalar(protocol));
        }

        return result;
    }

    public static RuntimeArray getservbyport(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        int port = args[0].scalar().getInt();
        String protocol = args[1].toString();

        Map<Integer, String> commonServices = Map.of(
                80, "http", 443, "https", 21, "ftp", 22, "ssh",
                23, "telnet", 25, "smtp", 53, "dns", 110, "pop3",
                143, "imap", 161, "snmp"
        );

        RuntimeArray result = new RuntimeArray();
        String service = commonServices.get(port);
        if (service != null) {
            RuntimeArray.push(result, new RuntimeScalar(service));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(port));
            RuntimeArray.push(result, new RuntimeScalar(protocol));
        }

        return result;
    }

    public static RuntimeBase getprotobyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        String protocol = args[0].toString().toLowerCase();

        Map<String, Integer> protocols = Map.of(
                "tcp", 6, "udp", 17, "icmp", 1, "ip", 0
        );

        Integer protoNum = protocols.get(protocol);
        if (protoNum == null) {
            // Not found — return empty list or undef in scalar context
            if (ctx == 1) return RuntimeScalarCache.scalarUndef;
            return new RuntimeArray();
        }

        if (ctx == 1) {
            // Scalar context: return just the protocol number
            return new RuntimeScalar(protoNum);
        }

        // List context: return (name, aliases, proto_number)
        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar(protocol));
        RuntimeArray.push(result, new RuntimeScalar(""));
        RuntimeArray.push(result, new RuntimeScalar(protoNum));
        return result;
    }

    public static RuntimeArray getprotobynumber(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();
        int protoNum = args[0].scalar().getInt();

        Map<Integer, String> protocols = Map.of(
                6, "tcp", 17, "udp", 1, "icmp", 0, "ip"
        );

        RuntimeArray result = new RuntimeArray();
        String protocol = protocols.get(protoNum);
        if (protocol != null) {
            RuntimeArray.push(result, new RuntimeScalar(protocol));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(protoNum));
        }

        return result;
    }

    private static List<String> getSystemUsers() {
        List<String> users = new ArrayList<>();

        try {
            if (IS_WINDOWS) {
                Process proc = Runtime.getRuntime().exec(new String[]{"net", "user"});
                try (Scanner scanner = new Scanner(proc.getInputStream())) {
                    boolean inUserList = false;
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();

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

                            if (uid <= 65534) {
                                users.add(username);
                            }
                        }
                    }
                } catch (Exception e) {
                    users.add("root");
                    users.add(System.getProperty("user.name"));
                }
            }
        } catch (Exception e) {
            users.add(System.getProperty("user.name"));
        }

        String currentUser = System.getProperty("user.name");
        if (!users.contains(currentUser)) {
            users.add(currentUser);
        }

        return users;
    }

    private static List<String> getSystemGroups() {
        List<String> groups = new ArrayList<>();

        try {
            if (IS_WINDOWS) {
                groups.addAll(Arrays.asList("Users", "Administrators", "Guests", "Power Users"));
            } else {
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

                            if (gid <= 65534) {
                                groups.add(groupname);
                            }
                        }
                    }
                } catch (Exception e) {
                    groups.addAll(Arrays.asList("root", "users", "wheel"));
                }
            }
        } catch (Exception e) {
            groups.add(IS_WINDOWS ? "Users" : "users");
        }

        return groups;
    }

    // ================== System V IPC Functions ==================

    public static RuntimeScalar msgctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        int cmd = args[1].scalar().getInt();
        RuntimeBase buf = args[2];

        switch (cmd) {
            case 0: // IPC_STAT
                if (messageQueues.containsKey(msqid)) {
                    return new RuntimeScalar(0);
                }
                break;
            case 1: // IPC_SET
                if (messageQueues.containsKey(msqid)) {
                    return new RuntimeScalar(0);
                }
                break;
            case 2: // IPC_RMID
                if (messageQueues.remove(msqid) != null) {
                    return new RuntimeScalar(0);
                }
                break;
        }
        return new RuntimeScalar(-1);
    }

    public static RuntimeScalar msgget(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int msgflg = args[1].scalar().getInt();

        int msqid = nextIpcId++;
        RuntimeArray msgQueue = new RuntimeArray();
        messageQueues.put(msqid, msgQueue);

        return new RuntimeScalar(msqid);
    }

    public static RuntimeScalar msgrcv(int ctx, RuntimeBase... args) {
        if (args.length < 5) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        RuntimeBase msg = args[1];
        int msgsz = args[2].scalar().getInt();
        int msgtyp = args[3].scalar().getInt();
        int msgflg = args[4].scalar().getInt();

        RuntimeArray msgQueue = messageQueues.get(msqid);
        if (msgQueue == null || msgQueue.size() == 0) {
            return new RuntimeScalar(-1);
        }

        RuntimeBase message = msgQueue.get(0);
        msgQueue.elements.remove(0);

        return new RuntimeScalar(message.toString().length());
    }

    public static RuntimeScalar msgsnd(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int msqid = args[0].scalar().getInt();
        RuntimeBase msgp = args[1];
        int msgsz = args[2].scalar().getInt();

        RuntimeArray msgQueue = messageQueues.get(msqid);
        if (msgQueue == null) {
            return new RuntimeScalar(-1);
        }

        RuntimeArray.push(msgQueue, msgp);
        return new RuntimeScalar(0);
    }

    public static RuntimeScalar semctl(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int semid = args[0].scalar().getInt();
        int semnum = args[1].scalar().getInt();
        int cmd = args[2].scalar().getInt();

        RuntimeArray semArray = semaphores.get(semid);
        if (semArray == null) {
            return new RuntimeScalar(-1);
        }

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

    public static RuntimeScalar semget(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int nsems = args[1].scalar().getInt();
        int semflg = args[2].scalar().getInt();

        int semid = nextIpcId++;
        RuntimeArray semArray = new RuntimeArray();
        for (int i = 0; i < nsems; i++) {
            RuntimeArray.push(semArray, new RuntimeScalar(0));
        }
        semaphores.put(semid, semArray);

        return new RuntimeScalar(semid);
    }

    public static RuntimeScalar semop(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeScalar(-1);

        int semid = args[0].scalar().getInt();
        RuntimeBase sops = args[1];

        RuntimeArray semArray = semaphores.get(semid);
        if (semArray == null) {
            return new RuntimeScalar(-1);
        }

        return new RuntimeScalar(0);
    }

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

    public static RuntimeScalar shmget(int ctx, RuntimeBase... args) {
        if (args.length < 3) return new RuntimeScalar(-1);

        int key = args[0].scalar().getInt();
        int size = args[1].scalar().getInt();
        int shmflg = args[2].scalar().getInt();

        int shmid = nextIpcId++;
        RuntimeArray shmSeg = new RuntimeArray();
        for (int i = 0; i < size; i++) {
            RuntimeArray.push(shmSeg, new RuntimeScalar(0));
        }
        sharedMemory.put(shmid, shmSeg);

        return new RuntimeScalar(shmid);
    }

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

        StringBuilder data = new StringBuilder();
        for (int i = pos; i < Math.min(pos + size, shmSeg.size()); i++) {
            data.append((char) shmSeg.get(i).scalar().getInt());
        }

        if (var instanceof RuntimeScalar) {
            ((RuntimeScalar) var).set(data.toString());
        }

        return new RuntimeScalar(data.length());
    }

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

        byte[] bytes = string.getBytes();
        int writeSize = Math.min(size, bytes.length);

        while (shmSeg.size() <= pos + writeSize) {
            RuntimeArray.push(shmSeg, new RuntimeScalar(0));
        }

        for (int i = 0; i < writeSize; i++) {
            shmSeg.elements.set(pos + i, new RuntimeScalar(bytes[i] & 0xFF));
        }

        return new RuntimeScalar(writeSize);
    }

    // ================== Network Enumeration Functions ==================

    public static RuntimeScalar endhostent(int ctx, RuntimeBase... args) {
        hostIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endnetent(int ctx, RuntimeBase... args) {
        netIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endprotoent(int ctx, RuntimeBase... args) {
        protoIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar endservent(int ctx, RuntimeBase... args) {
        servIterator.remove();
        return new RuntimeScalar(1);
    }

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

        return new RuntimeArray();
    }

    public static RuntimeArray getnetbyaddr(int ctx, RuntimeBase... args) {
        if (args.length < 2) return new RuntimeArray();

        String addr = args[0].toString();
        int addrtype = args[1].scalar().getInt();

        RuntimeArray result = new RuntimeArray();
        RuntimeArray.push(result, new RuntimeScalar("loopback"));
        RuntimeArray.push(result, new RuntimeScalar(""));
        RuntimeArray.push(result, new RuntimeScalar(addrtype));
        RuntimeArray.push(result, new RuntimeScalar("127.0.0.1"));

        return result;
    }

    public static RuntimeArray getnetbyname(int ctx, RuntimeBase... args) {
        if (args.length < 1) return new RuntimeArray();

        String name = args[0].toString();

        RuntimeArray result = new RuntimeArray();
        if (name.equals("loopback") || name.equals("localhost")) {
            RuntimeArray.push(result, new RuntimeScalar(name));
            RuntimeArray.push(result, new RuntimeScalar(""));
            RuntimeArray.push(result, new RuntimeScalar(2));
            RuntimeArray.push(result, new RuntimeScalar("127.0.0.1"));
        }

        return result;
    }

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

        return new RuntimeArray();
    }

    public static RuntimeBase getprotoent(int ctx, RuntimeBase... args) {
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

        return new RuntimeArray();
    }

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

        return new RuntimeArray();
    }

    public static RuntimeScalar sethostent(int ctx, RuntimeBase... args) {
        hostIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar setnetent(int ctx, RuntimeBase... args) {
        netIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar setprotoent(int ctx, RuntimeBase... args) {
        protoIterator.remove();
        return new RuntimeScalar(1);
    }

    public static RuntimeScalar setservent(int ctx, RuntimeBase... args) {
        servIterator.remove();
        return new RuntimeScalar(1);
    }

    private static List<String> getSystemHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("localhost");
        hosts.add("127.0.0.1");

        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            if (!hosts.contains(hostname)) {
                hosts.add(hostname);
            }
        } catch (Exception e) {
        }

        return hosts;
    }
}
