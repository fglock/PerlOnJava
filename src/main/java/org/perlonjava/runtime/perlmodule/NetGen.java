package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.LIST;

/**
 * XS compatibility for the socket constants and address helpers used by the
 * historical Net-ext distribution.  The socket implementation itself remains
 * in PerlOnJava's core Socket module.
 */
public final class NetGen extends PerlModuleBase {
    public static final String XS_VERSION = "1.011";

    private static final String[] ERRNO_CONSTANTS = {
            "EINPROGRESS", "EALREADY", "ENOTSOCK", "EDESTADDRREQ",
            "EMSGSIZE", "EPROTOTYPE", "ENOPROTOOPT", "EPROTONOSUPPORT",
            "ESOCKTNOSUPPORT", "EOPNOTSUPP", "EPFNOSUPPORT", "EAFNOSUPPORT",
            "EADDRINUSE", "EADDRNOTAVAIL", "ENETDOWN", "ENETUNREACH",
            "ENETRESET", "ECONNABORTED", "ECONNRESET", "ENOBUFS", "EISCONN",
            "ENOTCONN", "ESHUTDOWN", "ETOOMANYREFS", "ETIMEDOUT",
            "ECONNREFUSED", "EHOSTDOWN", "EHOSTUNREACH", "ENOSR", "ETIME",
            "EBADMSG", "EPROTO", "ENODATA", "ENOSTR", "EAGAIN",
            "EWOULDBLOCK", "ENOENT", "EINVAL", "EBADF"
    };

    private static final String[] SOCKET_CONSTANTS = {
            "SHUT_RD", "SHUT_WR", "SHUT_RDWR", "SOL_SOCKET", "SOMAXCONN",
            "MSG_OOB",
            "SO_ACCEPTCONN", "SO_BROADCAST", "SO_ERROR", "SO_KEEPALIVE",
            "SO_LINGER", "SO_RCVBUF", "SO_REUSEADDR", "SO_REUSEPORT",
            "SO_SNDBUF", "SO_TYPE", "SOCK_STREAM", "SOCK_DGRAM", "SOCK_RAW",
            "AF_UNSPEC", "AF_UNIX", "AF_INET", "PF_UNSPEC", "PF_UNIX",
            "PF_INET", "IPPROTO_IP", "IPPROTO_ICMP", "IPPROTO_TCP",
            "IPPROTO_UDP", "IP_TOS", "IP_TTL", "TCP_NODELAY"
    };

    private static final String[] ZERO_CONSTANTS = {
            "VAL_O_NONBLOCK", "VAL_EAGAIN", "RD_NODATA", "EOF_NONBLOCK",
            "SO_DEBUG", "SO_DONTROUTE", "SO_EXPANDED_RIGHTS", "SO_FAMILY",
            "SO_OOBINLINE", "SO_PAIRABLE", "SO_RCVLOWAT", "SO_RCVTIMEO",
            "SO_SNDLOWAT", "SO_SNDTIMEO", "SO_STATE", "SO_USELOOPBACK",
            "SO_XSE", "SOCK_RDM", "SOCK_SEQPACKET", "AF_IMPLINK", "AF_PUP",
            "AF_CHAOS", "AF_NS", "AF_ISO", "AF_OSI", "AF_ECMA", "AF_DATAKIT",
            "AF_CCITT", "AF_SNA", "AF_DECnet", "AF_DLI", "AF_LAT",
            "AF_HYLINK", "AF_APPLETALK", "AF_ROUTE", "AF_LINK", "AF_NETMAN",
            "AF_X25", "AF_CTF", "AF_WAN", "AF_USER", "AF_LAST", "AF_LOCAL",
            "PF_IMPLINK", "PF_PUP", "PF_CHAOS", "PF_NS", "PF_ISO", "PF_OSI",
            "PF_ECMA", "PF_DATAKIT", "PF_CCITT", "PF_SNA", "PF_DECnet",
            "PF_DLI", "PF_LAT", "PF_HYLINK", "PF_APPLETALK", "PF_ROUTE",
            "PF_LINK", "PF_NETMAN", "PF_X25", "PF_CTF", "PF_WAN", "PF_USER",
            "PF_LAST", "PF_LOCAL"
    };

    private static final String[] INET_ZERO_CONSTANTS = {
            "DEFTTL", "ICMP_ADVLENMIN", "ICMP_ECHO", "ICMP_ECHOREPLY",
            "ICMP_IREQ", "ICMP_IREQREPLY", "ICMP_MASKLEN", "ICMP_MASKREPLY",
            "ICMP_MASKREQ", "ICMP_MAXTYPE", "ICMP_MINLEN", "ICMP_PARAMPROB",
            "ICMP_REDIRECT", "ICMP_REDIRECT_HOST", "ICMP_REDIRECT_NET",
            "ICMP_REDIRECT_TOSHOST", "ICMP_REDIRECT_TOSNET",
            "ICMP_SOURCEQUENCH", "ICMP_TIMXCEED", "ICMP_TIMXCEED_INTRANS",
            "ICMP_TIMXCEED_REASS", "ICMP_TSLEN", "ICMP_TSTAMP",
            "ICMP_TSTAMPREPLY", "ICMP_UNREACH", "ICMP_UNREACH_HOST",
            "ICMP_UNREACH_NEEDFRAG", "ICMP_UNREACH_NET", "ICMP_UNREACH_PORT",
            "ICMP_UNREACH_PROTOCOL", "ICMP_UNREACH_SRCFAIL", "IN_CLASSA_HOST",
            "IN_CLASSA_MAX", "IN_CLASSA_NET", "IN_CLASSA_NSHIFT",
            "IN_CLASSA_SUBHOST", "IN_CLASSA_SUBNET", "IN_CLASSA_SUBNSHIFT",
            "IN_CLASSB_HOST", "IN_CLASSB_MAX", "IN_CLASSB_NET",
            "IN_CLASSB_NSHIFT", "IN_CLASSB_SUBHOST", "IN_CLASSB_SUBNET",
            "IN_CLASSB_SUBNSHIFT", "IN_CLASSC_HOST", "IN_CLASSC_MAX",
            "IN_CLASSC_NET", "IN_CLASSC_NSHIFT", "IN_CLASSD_HOST",
            "IN_CLASSD_NET", "IN_CLASSD_NSHIFT", "IN_LOOPBACKNET", "IPFRAGTTL",
            "IPOPT_CIPSO", "IPOPT_CONTROL", "IPOPT_DEBMEAS", "IPOPT_EOL",
            "IPOPT_LSRR", "IPOPT_MINOFF", "IPOPT_NOP", "IPOPT_OFFSET",
            "IPOPT_OLEN", "IPOPT_OPTVAL", "IPOPT_RESERVED1", "IPOPT_RESERVED2",
            "IPOPT_RIPSO_AUX", "IPOPT_RR", "IPOPT_SATID", "IPOPT_SECURITY",
            "IPOPT_SECUR_CONFID", "IPOPT_SECUR_EFTO", "IPOPT_SECUR_MMMM",
            "IPOPT_SECUR_RESTR", "IPOPT_SECUR_SECRET", "IPOPT_SECUR_TOPSECRET",
            "IPOPT_SECUR_UNCLASS", "IPOPT_SSRR", "IPOPT_TS", "IPOPT_TS_PRESPEC",
            "IPOPT_TS_TSANDADDR", "IPOPT_TS_TSONLY", "IPPORT_RESERVED",
            "IPPORT_TIMESERVER", "IPPORT_USERRESERVED", "IPPROTO_EGP",
            "IPPROTO_EON", "IPPROTO_GGP", "IPPROTO_HELLO", "IPPROTO_IDP",
            "IPPROTO_IGMP", "IPPROTO_IPIP", "IPPROTO_MAX", "IPPROTO_PUP",
            "IPPROTO_RAW", "IPPROTO_RSVP", "IPPROTO_TP", "IPTOS_LOWDELAY",
            "IPTOS_PREC_CRITIC_ECP", "IPTOS_PREC_FLASH",
            "IPTOS_PREC_FLASHOVERRIDE", "IPTOS_PREC_IMMEDIATE",
            "IPTOS_PREC_INTERNETCONTROL", "IPTOS_PREC_NETCONTROL",
            "IPTOS_PREC_PRIORITY", "IPTOS_PREC_ROUTINE", "IPTOS_RELIABILITY",
            "IPTOS_THROUGHPUT", "IPTTLDEC", "IPVERSION", "IP_ADD_MEMBERSHIP",
            "IP_DEFAULT_MULTICAST_LOOP", "IP_DEFAULT_MULTICAST_TTL", "IP_DF",
            "IP_DROP_MEMBERSHIP", "IP_HDRINCL", "IP_MAXPACKET",
            "IP_MAX_MEMBERSHIPS", "IP_MF", "IP_MSS", "IP_MULTICAST_IF",
            "IP_MULTICAST_LOOP", "IP_MULTICAST_TTL", "IP_OPTIONS",
            "IP_RECVDSTADDR", "IP_RECVOPTS", "IP_RECVRETOPTS", "IP_RETOPTS",
            "MAXTTL", "MAX_IPOPTLEN", "MINTTL", "SUBNETSHIFT",
            "INADDR_ALLHOSTS_GROUP", "INADDR_ALLRTRS_GROUP",
            "INADDR_MAX_LOCAL_GROUP", "INADDR_UNSPEC_GROUP"
    };

    private NetGen() {
        super("Net::Gen", false);
    }

    public static void initialize() {
        ModuleOperators.require(new RuntimeScalar("Errno.pm"));
        ModuleOperators.require(new RuntimeScalar("Socket.pm"));

        NetGen module = new NetGen();
        try {
            module.registerMethod("_pack_sockaddr_in", null);
            module.registerMethod("unpack_sockaddr_in", null);
            module.registerMethod("pack_sockaddr", null);
            module.registerMethod("unpack_sockaddr", null);
            module.registerMethod("_constant_zero", "");
            module.registerMethodInPackage(
                    "Net::Inet", "_pack_sockaddr_in", "_pack_sockaddr_in");
            module.registerMethodInPackage(
                    "Net::Inet", "unpack_sockaddr_in", "unpack_sockaddr_in");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        for (String name : ERRNO_CONSTANTS) {
            alias("Errno::" + name, "Net::Gen::" + name);
        }
        for (String name : SOCKET_CONSTANTS) {
            alias("Socket::" + name, "Net::Gen::" + name);
        }
        for (String name : ZERO_CONSTANTS) {
            alias("Net::Gen::_constant_zero", "Net::Gen::" + name);
        }
        for (String name : INET_ZERO_CONSTANTS) {
            alias("Net::Gen::_constant_zero", "Net::Inet::" + name);
        }
        for (String name : new String[]{
                "IPPROTO_IP", "IPPROTO_ICMP", "IPPROTO_TCP", "IPPROTO_UDP",
                "IP_TOS", "IP_TTL", "INADDR_ANY", "INADDR_LOOPBACK",
                "INADDR_BROADCAST"}) {
            alias("Socket::" + name, "Net::Inet::" + name);
        }
    }

    private static void alias(String source, String destination) {
        GlobalVariable.getGlobalCodeRef(destination)
                .set(GlobalVariable.getGlobalCodeRef(source));
    }

    public static RuntimeList _constant_zero(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList _pack_sockaddr_in(RuntimeArray args, int ctx) {
        RuntimeArray socketArgs = new RuntimeArray();
        socketArgs.add(args.get(1));
        socketArgs.add(args.get(2));
        return Socket.pack_sockaddr_in(socketArgs, ctx);
    }

    public static RuntimeList unpack_sockaddr_in(RuntimeArray args, int ctx) {
        RuntimeList unpacked = Socket.unpack_sockaddr_in(args, LIST);
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(Socket.AF_INET));
        if (unpacked.size() >= 2) {
            result.add(unpacked.elements.get(0));
            result.add(unpacked.elements.get(1));
        }
        return result;
    }

    public static RuntimeList pack_sockaddr(RuntimeArray args, int ctx) {
        int family = args.get(0).getInt();
        String address = args.get(1).toString();
        byte[] bytes = address.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] packed = new byte[bytes.length + 2];
        packed[0] = 0;
        packed[1] = (byte) family;
        System.arraycopy(bytes, 0, packed, 2, bytes.length);
        return new RuntimeScalar(new String(
                packed, java.nio.charset.StandardCharsets.ISO_8859_1)).getList();
    }

    public static RuntimeList unpack_sockaddr(RuntimeArray args, int ctx) {
        byte[] packed = args.get(0).toString()
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(packed.length > 1 ? packed[1] & 0xff : 0));
        result.add(new RuntimeScalar(new String(
                packed, Math.min(2, packed.length), Math.max(0, packed.length - 2),
                java.nio.charset.StandardCharsets.ISO_8859_1)));
        return result;
    }
}
