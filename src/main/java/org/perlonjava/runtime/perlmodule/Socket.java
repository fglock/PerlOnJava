package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Socket module implementation providing socket-related functions
 * like pack_sockaddr_in, unpack_sockaddr_in, inet_aton, inet_ntoa, etc.
 * This module can be loaded with XSLoader.
 */
public class Socket extends PerlModuleBase {

    // Platform detection
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean IS_MAC = OS.contains("mac") || OS.contains("darwin");
    private static final boolean IS_WINDOWS = OS.contains("win");

    // Socket constants - platform-dependent values
    public static final int AF_INET = 2;
    public static final int AF_INET6 = IS_MAC ? 30 : IS_WINDOWS ? 23 : 10;
    public static final int AF_UNIX = 1;
    public static final int PF_INET = 2;
    public static final int PF_INET6 = AF_INET6;
    public static final int PF_UNIX = 1;
    public static final int PF_UNSPEC = 0;
    public static final int SOMAXCONN = IS_WINDOWS ? 0x7fffffff : 128;
    public static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
    public static final int SOCK_RAW = 3;
    public static final int SOL_SOCKET = (IS_MAC || IS_WINDOWS) ? 0xFFFF : 1;
    public static final int SO_REUSEADDR = (IS_MAC || IS_WINDOWS) ? 4 : 2;
    public static final int SO_KEEPALIVE = (IS_MAC || IS_WINDOWS) ? 8 : 9;
    public static final int SO_BROADCAST = (IS_MAC || IS_WINDOWS) ? 0x20 : 6;
    public static final int SO_LINGER = (IS_MAC || IS_WINDOWS) ? 0x80 : 13;
    public static final int SO_ERROR = (IS_MAC || IS_WINDOWS) ? 0x1007 : 4;
    public static final int SO_TYPE = (IS_MAC || IS_WINDOWS) ? 0x1008 : 3;
    public static final int TCP_NODELAY = 1;
    public static final int IPPROTO_TCP = 6;
    public static final int IPPROTO_UDP = 17;
    public static final int IPPROTO_ICMP = 1;
    public static final int IPPROTO_IP = 0;
    public static final int IPPROTO_IPV6 = IS_MAC ? 41 : IS_WINDOWS ? 41 : 41;
    public static final int IP_TOS = IS_MAC ? 3 : 1;
    public static final int IP_TTL = IS_MAC ? 4 : 2;
    public static final int SHUT_RD = 0;
    public static final int SHUT_WR = 1;
    public static final int SHUT_RDWR = 2;
    // getaddrinfo/getnameinfo constants
    public static final int AI_PASSIVE = 1;
    public static final int AI_CANONNAME = 2;
    public static final int AI_NUMERICHOST = 4;
    public static final int AI_ADDRCONFIG = 0x0400;
    public static final int NI_NUMERICHOST = 1;
    public static final int NI_NUMERICSERV = 2;
    public static final int NI_DGRAM = 16;
    public static final int NIx_NOHOST = 1;
    public static final int NIx_NOSERV = 2;
    public static final int EAI_NONAME = IS_WINDOWS ? 11001 : 8;
    // IPV6 constants
    public static final int IPV6_V6ONLY = (IS_MAC || IS_WINDOWS) ? 27 : 26;
    public static final int SO_REUSEPORT = IS_MAC ? 0x0200 : 15;  // not available on Windows
    // INADDR constants as 4-byte packed binary strings
    public static final String INADDR_ANY = "\0\0\0\0";           // 0.0.0.0
    public static final String INADDR_LOOPBACK = "\177\0\0\1";    // 127.0.0.1
    public static final String INADDR_BROADCAST = "\377\377\377\377"; // 255.255.255.255

    public Socket() {
        super("Socket", false);
    }

    public static void initialize() {
        Socket socket = new Socket();

        try {
            // Register socket functions
            socket.registerMethod("pack_sockaddr_in", null);
            socket.registerMethod("unpack_sockaddr_in", null);
            socket.registerMethod("inet_aton", null);
            socket.registerMethod("inet_ntoa", null);
            socket.registerMethod("sockaddr_in", null);
            socket.registerMethod("getnameinfo", null);
            socket.registerMethod("getaddrinfo", null);
            socket.registerMethod("sockaddr_family", null);
            socket.registerMethod("pack_sockaddr_un", null);
            socket.registerMethod("unpack_sockaddr_un", null);
            socket.registerMethod("sockaddr_un", null);

            // Register constants as subroutines with empty prototype (like use constant)
            socket.registerMethod("AF_INET", "");
            socket.registerMethod("AF_INET6", "");
            socket.registerMethod("AF_UNIX", "");
            socket.registerMethod("PF_INET", "");
            socket.registerMethod("PF_INET6", "");
            socket.registerMethod("PF_UNIX", "");
            socket.registerMethod("PF_UNSPEC", "");
            socket.registerMethod("SOMAXCONN", "");
            socket.registerMethod("SOCK_STREAM", "");
            socket.registerMethod("SOCK_DGRAM", "");
            socket.registerMethod("SOCK_RAW", "");
            socket.registerMethod("SOL_SOCKET", "");
            socket.registerMethod("SO_REUSEADDR", "");
            socket.registerMethod("SO_KEEPALIVE", "");
            socket.registerMethod("SO_BROADCAST", "");
            socket.registerMethod("SO_LINGER", "");
            socket.registerMethod("SO_ERROR", "");
            socket.registerMethod("SO_TYPE", "");
            socket.registerMethod("TCP_NODELAY", "");
            socket.registerMethod("IPPROTO_TCP", "");
            socket.registerMethod("IPPROTO_UDP", "");
            socket.registerMethod("IPPROTO_ICMP", "");
            socket.registerMethod("IPPROTO_IP", "");
            socket.registerMethod("IPPROTO_IPV6", "");
            socket.registerMethod("IP_TOS", "");
            socket.registerMethod("IP_TTL", "");
            socket.registerMethod("SHUT_RD", "");
            socket.registerMethod("SHUT_WR", "");
            socket.registerMethod("SHUT_RDWR", "");
            socket.registerMethod("INADDR_ANY", "");
            socket.registerMethod("INADDR_LOOPBACK", "");
            socket.registerMethod("INADDR_BROADCAST", "");
            socket.registerMethod("AI_PASSIVE", "");
            socket.registerMethod("AI_CANONNAME", "");
            socket.registerMethod("AI_NUMERICHOST", "");
            socket.registerMethod("AI_ADDRCONFIG", "");
            socket.registerMethod("NI_NUMERICHOST", "");
            socket.registerMethod("NI_NUMERICSERV", "");
            socket.registerMethod("NI_DGRAM", "");
            socket.registerMethod("NIx_NOHOST", "");
            socket.registerMethod("NIx_NOSERV", "");
            socket.registerMethod("EAI_NONAME", "");
            socket.registerMethod("IPV6_V6ONLY", "");
            socket.registerMethod("SO_REUSEPORT", "");

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Socket method: " + e.getMessage());
        }
    }

    /**
     * pack_sockaddr_in(PORT, IP_ADDRESS)
     * Packs a port and IP address into a sockaddr_in structure
     */
    public static RuntimeList pack_sockaddr_in(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("Not enough arguments for pack_sockaddr_in");
        }

        try {
            int port = args.get(0).getInt();
            String ipAddress = args.get(1).toString();

            // Handle special case where IP is passed as binary (4 bytes)
            byte[] ipBytes;
            if (ipAddress.length() == 4) {
                // Already in binary format
                ipBytes = ipAddress.getBytes(StandardCharsets.ISO_8859_1);
            } else if (ipAddress.length() == 1) {
                // Handle case where gethostbyname returns a single character in scalar context
                // This is likely a bug - let's use localhost IP as fallback
                ipBytes = new byte[]{127, 0, 0, 1}; // 127.0.0.1
            } else {
                // Parse as dotted decimal notation
                String[] parts = ipAddress.split("\\.");
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Invalid IP address format: '" + ipAddress + "' (length: " + ipAddress.length() + ")");
                }
                ipBytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    ipBytes[i] = (byte) Integer.parseInt(parts[i]);
                }
            }

            // Create sockaddr_in structure: 2 bytes family, 2 bytes port, 4 bytes IP, 8 bytes padding
            byte[] sockaddr = new byte[16];

            // Family: AF_INET = 2 (network byte order)
            sockaddr[0] = 0;
            sockaddr[1] = 2;

            // Port (network byte order - big endian)
            sockaddr[2] = (byte) ((port >> 8) & 0xFF);
            sockaddr[3] = (byte) (port & 0xFF);

            // IP address (4 bytes)
            System.arraycopy(ipBytes, 0, sockaddr, 4, 4);

            // Padding (8 bytes of zeros)
            for (int i = 8; i < 16; i++) {
                sockaddr[i] = 0;
            }

            return new RuntimeScalar(new String(sockaddr, StandardCharsets.ISO_8859_1)).getList();

        } catch (Exception e) {
            throw new RuntimeException("pack_sockaddr_in failed: " + e.getMessage(), e);
        }
    }

    /**
     * unpack_sockaddr_in(SOCKADDR)
     * Unpacks a sockaddr_in structure into port and IP address
     * Returns (port, ip_address) in list context, or just port in scalar context
     */
    public static RuntimeList unpack_sockaddr_in(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("Not enough arguments for unpack_sockaddr_in");
        }

        try {
            String sockaddrStr = args.get(0).toString();
            byte[] sockaddr = sockaddrStr.getBytes(StandardCharsets.ISO_8859_1);

            if (sockaddr.length < 8) {
                throw new IllegalArgumentException("Invalid sockaddr_in structure");
            }

            // Extract port (bytes 2-3, network byte order)
            int port = ((sockaddr[2] & 0xFF) << 8) | (sockaddr[3] & 0xFF);

            // Extract IP address (bytes 4-7)
            byte[] ipBytes = new byte[4];
            System.arraycopy(sockaddr, 4, ipBytes, 0, 4);

            if (ctx == RuntimeContextType.LIST) {
                // Return (port, ip_address) in list context
                RuntimeList result = new RuntimeList();
                result.add(new RuntimeScalar(port));
                result.add(new RuntimeScalar(new String(ipBytes, StandardCharsets.ISO_8859_1))); // Return IP as binary
                return result;
            } else {
                // Return just port in scalar context
                return new RuntimeScalar(port).getList();
            }

        } catch (Exception e) {
            throw new RuntimeException("unpack_sockaddr_in failed: " + e.getMessage(), e);
        }
    }

    /**
     * inet_aton(HOSTNAME)
     * Converts a hostname or IP address to a 4-byte binary string
     */
    public static RuntimeList inet_aton(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return scalarUndef.getList();
        }

        try {
            String hostname = args.get(0).toString();
            InetAddress addr = InetAddress.getByName(hostname);
            byte[] ipBytes = addr.getAddress();

            if (ipBytes.length == 4) {
                return new RuntimeScalar(new String(ipBytes, StandardCharsets.ISO_8859_1)).getList();
            } else {
                // IPv6 not supported yet
                return scalarUndef.getList();
            }

        } catch (UnknownHostException e) {
            return scalarUndef.getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    /**
     * inet_ntoa(IP_ADDRESS)
     * Converts a 4-byte binary IP address to dotted decimal notation
     */
    public static RuntimeList inet_ntoa(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return scalarUndef.getList();
        }

        try {
            String ipBinary = args.get(0).toString();
            if (ipBinary.length() != 4) {
                return scalarUndef.getList();
            }

            byte[] ipBytes = ipBinary.getBytes(StandardCharsets.ISO_8859_1);
            String ipAddress = String.format("%d.%d.%d.%d",
                    ipBytes[0] & 0xFF, ipBytes[1] & 0xFF,
                    ipBytes[2] & 0xFF, ipBytes[3] & 0xFF);

            return new RuntimeScalar(ipAddress).getList();

        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    /**
     * sockaddr_in(PORT, IP_ADDRESS) - pack form (2 args)
     * sockaddr_in(SOCKADDR) - unpack form (1 arg)
     * Dual-purpose function matching Perl's sockaddr_in behavior.
     */
    public static RuntimeList sockaddr_in(RuntimeArray args, int ctx) {
        if (args.size() >= 2) {
            return pack_sockaddr_in(args, ctx);
        } else {
            return unpack_sockaddr_in(args, ctx);
        }
    }

    /**
     * getnameinfo(SOCKADDR, FLAGS)
     * Converts a socket address to a hostname and service name.
     * Returns ($err, $host, $service) in list context, matching Perl's Socket::getnameinfo.
     */
    public static RuntimeList getnameinfo(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar("Missing sockaddr argument"));
            return result;
        }

        try {
            String sockaddr = args.get(0).toString();
            int flags = args.size() > 1 ? args.get(1).getInt() : 0;

            if (sockaddr.length() < 8) {
                RuntimeList result = new RuntimeList();
                result.add(new RuntimeScalar("Invalid sockaddr structure"));
                return result;
            }

            byte[] sockBytes = sockaddr.getBytes(StandardCharsets.ISO_8859_1);

            // Extract port (bytes 2-3, big endian)
            int port = ((sockBytes[2] & 0xFF) << 8) | (sockBytes[3] & 0xFF);

            // Extract IP address (bytes 4-7)
            String ipAddress = String.format("%d.%d.%d.%d",
                    sockBytes[4] & 0xFF, sockBytes[5] & 0xFF,
                    sockBytes[6] & 0xFF, sockBytes[7] & 0xFF);

            // Resolve hostname based on NI_NUMERICHOST flag
            String hostname;
            if ((flags & NI_NUMERICHOST) != 0) {
                hostname = ipAddress;
            } else {
                try {
                    InetAddress addr = InetAddress.getByName(ipAddress);
                    hostname = addr.getHostName();
                } catch (Exception e) {
                    hostname = ipAddress;  // Fall back to IP if resolution fails
                }
            }

            // Resolve service name based on NI_NUMERICSERV flag
            String service = String.valueOf(port);

            // Return ($err, $hostname, $service) - $err is empty string on success
            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar(""));
            result.add(new RuntimeScalar(hostname));
            result.add(new RuntimeScalar(service));
            return result;

        } catch (Exception e) {
            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar(e.getMessage()));
            return result;
        }
    }

    // Constant methods
    public static RuntimeList AF_INET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AF_INET).getList();
    }

    public static RuntimeList PF_INET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PF_INET).getList();
    }

    public static RuntimeList SOCK_STREAM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SOCK_STREAM).getList();
    }

    public static RuntimeList SOCK_DGRAM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SOCK_DGRAM).getList();
    }

    public static RuntimeList SOL_SOCKET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SOL_SOCKET).getList();
    }

    public static RuntimeList SO_REUSEADDR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_REUSEADDR).getList();
    }

    public static RuntimeList AF_INET6(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AF_INET6).getList();
    }

    public static RuntimeList AF_UNIX(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AF_UNIX).getList();
    }

    public static RuntimeList PF_INET6(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PF_INET6).getList();
    }

    public static RuntimeList PF_UNIX(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PF_UNIX).getList();
    }

    public static RuntimeList PF_UNSPEC(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PF_UNSPEC).getList();
    }

    public static RuntimeList SOMAXCONN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SOMAXCONN).getList();
    }

    public static RuntimeList SOCK_RAW(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SOCK_RAW).getList();
    }

    public static RuntimeList SO_KEEPALIVE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_KEEPALIVE).getList();
    }

    public static RuntimeList SO_BROADCAST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_BROADCAST).getList();
    }

    public static RuntimeList SO_LINGER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_LINGER).getList();
    }

    public static RuntimeList SO_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_ERROR).getList();
    }

    public static RuntimeList SO_TYPE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_TYPE).getList();
    }

    public static RuntimeList TCP_NODELAY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(TCP_NODELAY).getList();
    }

    public static RuntimeList IPPROTO_TCP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPPROTO_TCP).getList();
    }

    public static RuntimeList IPPROTO_UDP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPPROTO_UDP).getList();
    }

    public static RuntimeList IPPROTO_ICMP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPPROTO_ICMP).getList();
    }

    public static RuntimeList IPPROTO_IP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPPROTO_IP).getList();
    }

    public static RuntimeList IPPROTO_IPV6(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPPROTO_IPV6).getList();
    }

    public static RuntimeList IP_TOS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IP_TOS).getList();
    }

    public static RuntimeList IP_TTL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IP_TTL).getList();
    }

    public static RuntimeList SHUT_RD(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SHUT_RD).getList();
    }

    public static RuntimeList SHUT_WR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SHUT_WR).getList();
    }

    public static RuntimeList SHUT_RDWR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SHUT_RDWR).getList();
    }

    public static RuntimeList INADDR_ANY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(INADDR_ANY).getList();
    }

    public static RuntimeList INADDR_LOOPBACK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(INADDR_LOOPBACK).getList();
    }

    public static RuntimeList INADDR_BROADCAST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(INADDR_BROADCAST).getList();
    }

    /**
     * getaddrinfo(HOST, SERVICE [, HINTS])
     * Resolves a hostname and service name to a list of socket address structures.
     * Returns ($err, @results) where each result is a hashref with:
     *   family, socktype, protocol, addr, canonname
     */
    public static RuntimeList getaddrinfo(RuntimeArray args, int ctx) {
        String host = args.size() > 0 && args.get(0).getDefinedBoolean() ? args.get(0).toString() : null;
        String service = args.size() > 1 && args.get(1).getDefinedBoolean() ? args.get(1).toString() : null;

        // Parse hints hashref if provided
        int hintFamily = 0;  // AF_UNSPEC
        int hintSocktype = 0;
        int hintProtocol = 0;
        int hintFlags = 0;
        if (args.size() > 2) {
            RuntimeScalar hintsArg = args.get(2);
            if (hintsArg.value instanceof RuntimeHash hintsHash) {
                RuntimeScalar fam = hintsHash.get("family");
                if (fam != null && fam.getDefinedBoolean()) hintFamily = fam.getInt();
                RuntimeScalar st = hintsHash.get("socktype");
                if (st != null && st.getDefinedBoolean()) hintSocktype = st.getInt();
                RuntimeScalar proto = hintsHash.get("protocol");
                if (proto != null && proto.getDefinedBoolean()) hintProtocol = proto.getInt();
                RuntimeScalar fl = hintsHash.get("flags");
                if (fl != null && fl.getDefinedBoolean()) hintFlags = fl.getInt();
            }
        }

        RuntimeList result = new RuntimeList();

        try {
            InetAddress[] addresses;
            if (host == null || host.isEmpty()) {
                if ((hintFlags & AI_PASSIVE) != 0) {
                    // Passive: use wildcard addresses
                    addresses = new InetAddress[]{
                            InetAddress.getByName("0.0.0.0")
                    };
                } else {
                    addresses = new InetAddress[]{
                            InetAddress.getByName("127.0.0.1")
                    };
                }
            } else {
                addresses = InetAddress.getAllByName(host);
            }

            // Parse port
            int port = 0;
            if (service != null && !service.isEmpty()) {
                try {
                    port = Integer.parseInt(service);
                } catch (NumberFormatException e) {
                    // Service name lookup - common services
                    switch (service.toLowerCase()) {
                        case "http": port = 80; break;
                        case "https": port = 443; break;
                        case "ftp": port = 21; break;
                        case "ssh": port = 22; break;
                        case "smtp": port = 25; break;
                        default: port = 0;
                    }
                }
            }

            // Success - empty error string
            result.add(new RuntimeScalar(""));

            for (InetAddress addr : addresses) {
                int family;
                byte[] sockaddrBytes;

                if (addr instanceof Inet6Address) {
                    if (hintFamily != 0 && hintFamily != AF_INET6) continue;
                    family = AF_INET6;
                    // Build sockaddr_in6: family(2) + port(2) + flowinfo(4) + addr(16) + scope(4) = 28 bytes
                    byte[] addrBytes = addr.getAddress();
                    sockaddrBytes = new byte[28];
                    // Family in big-endian (matches pack_sockaddr_in convention)
                    sockaddrBytes[0] = (byte) ((family >> 8) & 0xFF);
                    sockaddrBytes[1] = (byte) (family & 0xFF);
                    sockaddrBytes[2] = (byte) ((port >> 8) & 0xFF);
                    sockaddrBytes[3] = (byte) (port & 0xFF);
                    System.arraycopy(addrBytes, 0, sockaddrBytes, 8, 16);
                } else {
                    if (hintFamily != 0 && hintFamily != AF_INET) continue;
                    family = AF_INET;
                    // Build sockaddr_in: family(2) + port(2) + addr(4) + zero(8) = 16 bytes
                    byte[] addrBytes = addr.getAddress();
                    sockaddrBytes = new byte[16];
                    // Family in big-endian (matches pack_sockaddr_in convention)
                    sockaddrBytes[0] = (byte) ((family >> 8) & 0xFF);
                    sockaddrBytes[1] = (byte) (family & 0xFF);
                    sockaddrBytes[2] = (byte) ((port >> 8) & 0xFF);
                    sockaddrBytes[3] = (byte) (port & 0xFF);
                    System.arraycopy(addrBytes, 0, sockaddrBytes, 4, 4);
                }

                // Build result hashref
                RuntimeHash entry = new RuntimeHash();
                entry.put("family", new RuntimeScalar(family));
                entry.put("socktype", new RuntimeScalar(hintSocktype != 0 ? hintSocktype : SOCK_STREAM));
                entry.put("protocol", new RuntimeScalar(hintProtocol != 0 ? hintProtocol : 0));
                entry.put("addr", new RuntimeScalar(new String(sockaddrBytes, StandardCharsets.ISO_8859_1)));
                entry.put("canonname", new RuntimeScalar(addr.getCanonicalHostName()));

                // If no socktype hint, add both STREAM and DGRAM entries
                if (hintSocktype == 0) {
                    RuntimeHash entryDgram = new RuntimeHash();
                    entryDgram.put("family", new RuntimeScalar(family));
                    entryDgram.put("socktype", new RuntimeScalar(SOCK_DGRAM));
                    entryDgram.put("protocol", new RuntimeScalar(IPPROTO_UDP));
                    entryDgram.put("addr", new RuntimeScalar(new String(sockaddrBytes, StandardCharsets.ISO_8859_1)));
                    entryDgram.put("canonname", new RuntimeScalar(""));

                    entry.put("protocol", new RuntimeScalar(IPPROTO_TCP));
                    result.add(entry.createReference());
                    result.add(entryDgram.createReference());
                } else {
                    result.add(entry.createReference());
                }
            }

            return result;
        } catch (UnknownHostException e) {
            // Return error
            result.add(new RuntimeScalar("Name or service not known"));
            return result;
        } catch (Exception e) {
            result.add(new RuntimeScalar(e.getMessage()));
            return result;
        }
    }

    /**
     * sockaddr_family(SOCKADDR)
     * Returns the address family of a packed sockaddr structure.
     */
    public static RuntimeList sockaddr_family(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("Not enough arguments for sockaddr_family");
        }
        String sockaddr = args.get(0).toString();
        if (sockaddr.length() < 2) {
            return scalarUndef.getList();
        }
        byte[] bytes = sockaddr.getBytes(StandardCharsets.ISO_8859_1);
        // Family is stored in the first 2 bytes (big-endian, matching pack_sockaddr_in convention)
        int family = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        return new RuntimeScalar(family).getList();
    }

    /**
     * pack_sockaddr_un(PATH)
     * Packs a Unix domain socket path into a sockaddr_un structure.
     * The structure is: 2 bytes family (AF_UNIX) + path + null terminator.
     * Total size is 110 bytes on most platforms (matching struct sockaddr_un).
     */
    public static RuntimeList pack_sockaddr_un(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("Not enough arguments for pack_sockaddr_un");
        }

        String path = args.get(0).toString();
        // struct sockaddr_un is typically 110 bytes: 2 bytes family + 108 bytes path
        int SOCKADDR_UN_SIZE = 110;
        int SUN_PATH_MAX = SOCKADDR_UN_SIZE - 2;

        if (path.length() > SUN_PATH_MAX - 1) {
            throw new RuntimeException("socket path too long for pack_sockaddr_un (max " + (SUN_PATH_MAX - 1) + ")");
        }

        byte[] sockaddr = new byte[SOCKADDR_UN_SIZE];
        // Family: AF_UNIX = 1 (big-endian to match our pack_sockaddr_in convention)
        sockaddr[0] = (byte) ((AF_UNIX >> 8) & 0xFF);
        sockaddr[1] = (byte) (AF_UNIX & 0xFF);

        // Copy path bytes
        byte[] pathBytes = path.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(pathBytes, 0, sockaddr, 2, pathBytes.length);
        // Rest is already zero-filled (null terminator + padding)

        return new RuntimeScalar(new String(sockaddr, StandardCharsets.ISO_8859_1)).getList();
    }

    /**
     * unpack_sockaddr_un(SOCKADDR)
     * Unpacks a sockaddr_un structure into a Unix socket path.
     * Returns the path string.
     */
    public static RuntimeList unpack_sockaddr_un(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("Not enough arguments for unpack_sockaddr_un");
        }

        String sockaddrStr = args.get(0).toString();
        byte[] sockaddr = sockaddrStr.getBytes(StandardCharsets.ISO_8859_1);

        if (sockaddr.length < 3) {
            throw new RuntimeException("Bad arg length for Socket::unpack_sockaddr_un, length is " + sockaddr.length + ", should be at least 3");
        }

        // Extract path (starts at byte 2, null-terminated)
        int pathEnd = 2;
        while (pathEnd < sockaddr.length && sockaddr[pathEnd] != 0) {
            pathEnd++;
        }

        String path = new String(sockaddr, 2, pathEnd - 2, StandardCharsets.ISO_8859_1);
        return new RuntimeScalar(path).getList();
    }

    /**
     * sockaddr_un(PATH) - pack form (1 arg in scalar context or 1 arg in list context)
     * sockaddr_un(SOCKADDR) - unpack form (1 arg that looks like packed sockaddr)
     * Dual-purpose function matching Perl's sockaddr_un behavior.
     * In practice, if called with a path string, it packs; if called with a binary sockaddr, it unpacks.
     */
    public static RuntimeList sockaddr_un(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("Not enough arguments for sockaddr_un");
        }
        String arg = args.get(0).toString();
        byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);

        // Heuristic: if arg looks like a packed sockaddr_un (starts with AF_UNIX family bytes
        // and is at least 3 bytes), treat it as an unpack operation.
        // Otherwise, treat as a pack operation.
        if (bytes.length >= 3) {
            int family = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            if (family == AF_UNIX && bytes.length > 10) {
                // Likely a packed sockaddr_un — unpack it
                return unpack_sockaddr_un(args, ctx);
            }
        }
        // Otherwise, pack
        return pack_sockaddr_un(args, ctx);
    }

    // New constant methods
    public static RuntimeList AI_PASSIVE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AI_PASSIVE).getList();
    }

    public static RuntimeList AI_CANONNAME(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AI_CANONNAME).getList();
    }

    public static RuntimeList AI_NUMERICHOST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AI_NUMERICHOST).getList();
    }

    public static RuntimeList AI_ADDRCONFIG(RuntimeArray args, int ctx) {
        return new RuntimeScalar(AI_ADDRCONFIG).getList();
    }

    public static RuntimeList NI_NUMERICHOST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NI_NUMERICHOST).getList();
    }

    public static RuntimeList NI_NUMERICSERV(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NI_NUMERICSERV).getList();
    }

    public static RuntimeList NI_DGRAM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NI_DGRAM).getList();
    }

    public static RuntimeList NIx_NOHOST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NIx_NOHOST).getList();
    }

    public static RuntimeList NIx_NOSERV(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NIx_NOSERV).getList();
    }

    public static RuntimeList EAI_NONAME(RuntimeArray args, int ctx) {
        return new RuntimeScalar(EAI_NONAME).getList();
    }

    public static RuntimeList IPV6_V6ONLY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(IPV6_V6ONLY).getList();
    }

    public static RuntimeList SO_REUSEPORT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(SO_REUSEPORT).getList();
    }
}
