package org.perlonjava.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * The SocketIO class provides a simplified interface for socket operations,
 * supporting both client and server socket functionalities. It allows for
 * binding, connecting, listening, accepting connections, and reading/writing
 * data over sockets.
 */
public class SocketIO implements IOHandle {
    private Socket socket;
    private ServerSocket serverSocket;
    private SocketChannel socketChannel;
    private ServerSocketChannel serverSocketChannel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;
    private CharsetDecoderHelper decoderHelper;

    // Socket options storage: key is "level:optname", value is the option value
    private final Map<String, Integer> socketOptions;

    /**
     * Constructs a SocketIO instance for a client socket.
     *
     * @param socket the client socket to be used for communication
     */
    public SocketIO(Socket socket) {
        this.socket = socket;
        this.socketOptions = new HashMap<>();
        try {
            if (this.socket != null) {
                this.inputStream = this.socket.getInputStream();
                this.outputStream = this.socket.getOutputStream();
                // Get the socket channel for native socket option support
                this.socketChannel = this.socket.getChannel();
            }
        } catch (IOException e) {
            handleIOException(e, "Failed to initialize socket streams");
        }
    }

    /**
     * Constructs a SocketIO instance for a server socket.
     *
     * @param serverSocket the server socket to be used for accepting connections
     */
    public SocketIO(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.socketOptions = new HashMap<>();
        // Get the server socket channel for native socket option support
        this.serverSocketChannel = this.serverSocket.getChannel();
    }

    /**
     * Constructs a SocketIO instance for a server socket with explicit channel.
     *
     * @param serverSocket        the server socket to be used for accepting connections
     * @param serverSocketChannel the server socket channel for native socket option support
     */
    public SocketIO(ServerSocket serverSocket, ServerSocketChannel serverSocketChannel) {
        this.serverSocket = serverSocket;
        this.serverSocketChannel = serverSocketChannel;
        this.socketOptions = new HashMap<>();
    }

    /**
     * Binds the socket to a specific address and port.
     *
     * @param address the IP address to bind to
     * @param port    the port number to bind to
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    public RuntimeScalar bind(String address, int port) {
        try {
            if (socket != null) {
                socket.bind(new InetSocketAddress(address, port));
            } else if (serverSocket != null) {
                serverSocket.bind(new InetSocketAddress(address, port));
            } else {
                throw new IllegalStateException("No socket available to bind");
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "bind operation failed");
        }
    }

    /**
     * Connects the client socket to a remote address and port.
     *
     * @param address the remote IP address to connect to
     * @param port    the remote port number to connect to
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    public RuntimeScalar connect(String address, int port) {
        if (socket == null) {
            throw new IllegalStateException("No socket available to connect");
        }
        try {
            socket.connect(new InetSocketAddress(address, port));
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "connect operation failed");
        }
    }

    /**
     * Listens for incoming connections on the server socket with a specified backlog.
     *
     * @param backlog the maximum number of pending connections
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    public RuntimeScalar listen(int backlog) {
        if (serverSocket == null) {
            throw new IllegalStateException("No server socket available to listen");
        }
        try {
            serverSocket.setReceiveBufferSize(backlog);
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "listen operation failed");
            return scalarFalse;
        }
    }

    /**
     * Accepts a connection on the server socket and returns the remote address.
     *
     * @return a RuntimeScalar containing the remote address as a string, or false on failure
     */
    public RuntimeScalar accept() {
        if (serverSocket == null) {
            throw new IllegalStateException("No server socket available to accept connections");
        }
        try {
            Socket clientSocket = serverSocket.accept();
            InetSocketAddress remoteAddress = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
            InetAddress inetAddress = remoteAddress.getAddress();
            int port = remoteAddress.getPort();

            // Convert to a string representation
            String addressString = inetAddress.getHostAddress() + ":" + port;
            return new RuntimeScalar(addressString);
        } catch (IOException e) {
            handleIOException(e, "accept operation failed");
            return scalarFalse;
        }
    }

    /**
     * Emulates Perl's fileno function by returning a unique identifier for the socket.
     *
     * @return a RuntimeScalar containing the hash code of the socket's channel, or undefined if unavailable
     */
    @Override
    public RuntimeScalar fileno() {
        if (socket != null) {
            return new RuntimeScalar(socket.getChannel().hashCode());
        }
        return scalarUndef;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        try {
            if (inputStream != null) {
                if (decoderHelper == null) {
                    decoderHelper = new CharsetDecoderHelper();
                }

                StringBuilder result = new StringBuilder();

                // Keep reading while we need more data for multi-byte sequences
                do {
                    byte[] buffer = new byte[maxBytes];
                    int bytesRead = inputStream.read(buffer);

                    if (bytesRead == -1) {
                        isEOF = true;
                        // Decode any remaining bytes on EOF
                        String decoded = decoderHelper.decode(buffer, bytesRead, charset);
                        if (!decoded.isEmpty()) {
                            result.append(decoded);
                        }
                        break;
                    }

                    String decoded = decoderHelper.decode(buffer, bytesRead, charset);
                    result.append(decoded);

                    // Continue if we need more data to decode a complete character
                } while (decoderHelper.needsMoreData() && !isEOF);

                return new RuntimeScalar(result.toString());
            }
            throw new IllegalStateException("No input stream available");
        } catch (IOException e) {
            return handleIOException(e, "read operation failed");
        }
    }

    /**
     * Writes data to the socket.
     *
     * @param string the data to be written to the socket
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    @Override
    public RuntimeScalar write(String string) {
        var data = string.getBytes(StandardCharsets.ISO_8859_1);
        try {
            if (outputStream != null) {
                outputStream.write(data);
                return scalarTrue;
            }
            throw new IllegalStateException("No output stream available");
        } catch (IOException e) {
            return handleIOException(e, "write operation failed");
        }
    }

    /**
     * Flushes the output stream of the socket.
     *
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    @Override
    public RuntimeScalar flush() {
        try {
            if (outputStream != null) {
                outputStream.flush();
                return scalarTrue;
            }
            return scalarFalse;
        } catch (IOException e) {
            return handleIOException(e, "flush operation failed");
        }
    }

    /**
     * Checks if the end-of-file (EOF) has been reached on the input stream.
     *
     * @return a RuntimeScalar indicating EOF (true) or not (false)
     */
    @Override
    public RuntimeScalar eof() {
        return isEOF ? scalarTrue : scalarFalse;
    }

    /**
     * Closes the socket or server socket, releasing any associated resources.
     *
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    @Override
    public RuntimeScalar close() {
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
            return scalarTrue;
        } catch (IOException e) {
            return handleIOException(e, "close operation failed");
        }
    }

    /**
     * Get the local socket address (getsockname equivalent)
     * Returns a packed sockaddr_in structure
     */
    public RuntimeScalar getsockname() {
        try {
            InetSocketAddress localAddress = null;

            if (socket != null && socket.getLocalSocketAddress() instanceof InetSocketAddress) {
                localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
            } else if (serverSocket != null && serverSocket.getLocalSocketAddress() instanceof InetSocketAddress) {
                localAddress = (InetSocketAddress) serverSocket.getLocalSocketAddress();
            }

            if (localAddress != null) {
                return packSockaddrIn(localAddress);
            }

            return scalarUndef;
        } catch (Exception e) {
            return scalarUndef;
        }
    }

    /**
     * Get the remote socket address (getpeername equivalent)
     * Returns a packed sockaddr_in structure
     */
    public RuntimeScalar getpeername() {
        try {
            if (socket != null && socket.getRemoteSocketAddress() instanceof InetSocketAddress remoteAddress) {
                return packSockaddrIn(remoteAddress);
            }

            return scalarUndef;
        } catch (Exception e) {
            return scalarUndef;
        }
    }

    /**
     * Pack an InetSocketAddress into a Perl-compatible sockaddr_in structure
     * Format: 2 bytes family (AF_INET=2), 2 bytes port (network order), 4 bytes IP, 8 bytes padding
     */
    private RuntimeScalar packSockaddrIn(InetSocketAddress address) {
        try {
            byte[] sockaddr = new byte[16];

            // Family: AF_INET = 2 (network byte order)
            sockaddr[0] = 0;
            sockaddr[1] = 2;

            // Port (network byte order - big endian)
            int port = address.getPort();
            sockaddr[2] = (byte) ((port >> 8) & 0xFF);
            sockaddr[3] = (byte) (port & 0xFF);

            // IP address (4 bytes)
            byte[] ipBytes = address.getAddress().getAddress();
            System.arraycopy(ipBytes, 0, sockaddr, 4, 4);

            // Padding (8 bytes of zeros)
            for (int i = 8; i < 16; i++) {
                sockaddr[i] = 0;
            }

            return new RuntimeScalar(new String(sockaddr, StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            return scalarUndef;
        }
    }

    /**
     * Sets a socket option value using Java's native socket option support.
     * This provides better IPv4/IPv6 compatibility and proper socket handling.
     *
     * @param level   the socket level (e.g., SOL_SOCKET)
     * @param optname the option name (e.g., SO_REUSEADDR)
     * @param value   the option value
     * @return true if the option was set successfully, false otherwise
     */
    public boolean setSocketOption(int level, int optname, int value) {
        try {
            // Try to use Java's native socket option support first
            SocketOption<?> javaOption = mapToJavaSocketOption(level, optname);
            if (javaOption != null && socketChannel != null) {
                if (javaOption == StandardSocketOptions.SO_REUSEADDR) {
                    socketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, value != 0);
                    return true;
                } else if (javaOption == StandardSocketOptions.SO_KEEPALIVE) {
                    socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, value != 0);
                    return true;
                } else if (javaOption == StandardSocketOptions.TCP_NODELAY) {
                    socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, value != 0);
                    return true;
                } else if (javaOption == StandardSocketOptions.SO_RCVBUF) {
                    socketChannel.setOption(StandardSocketOptions.SO_RCVBUF, value);
                    return true;
                } else if (javaOption == StandardSocketOptions.SO_SNDBUF) {
                    socketChannel.setOption(StandardSocketOptions.SO_SNDBUF, value);
                    return true;
                }
            }

            // Fall back to manual storage for unsupported options
            String key = level + ":" + optname;
            socketOptions.put(key, value);
            return true;
        } catch (Exception e) {
            // Fall back to manual storage
            String key = level + ":" + optname;
            socketOptions.put(key, value);
            return true;
        }
    }

    /**
     * Gets a socket option value using Java's native socket option support.
     * This provides better IPv4/IPv6 compatibility and proper socket handling.
     *
     * @param level   the socket level (e.g., SOL_SOCKET)
     * @param optname the option name (e.g., SO_REUSEADDR)
     * @return the option value, or 0 if not set
     */
    public int getSocketOption(int level, int optname) {
        try {
            // Try to use Java's native socket option support first
            SocketOption<?> javaOption = mapToJavaSocketOption(level, optname);
            if (javaOption != null && socketChannel != null) {
                if (javaOption == StandardSocketOptions.SO_REUSEADDR) {
                    Boolean value = socketChannel.getOption(StandardSocketOptions.SO_REUSEADDR);
                    return value != null && value ? 1 : 0;
                } else if (javaOption == StandardSocketOptions.SO_KEEPALIVE) {
                    Boolean value = socketChannel.getOption(StandardSocketOptions.SO_KEEPALIVE);
                    return value != null && value ? 1 : 0;
                } else if (javaOption == StandardSocketOptions.TCP_NODELAY) {
                    Boolean value = socketChannel.getOption(StandardSocketOptions.TCP_NODELAY);
                    return value != null && value ? 1 : 0;
                } else if (javaOption == StandardSocketOptions.SO_RCVBUF) {
                    Integer value = socketChannel.getOption(StandardSocketOptions.SO_RCVBUF);
                    return value != null ? value : 0;
                } else if (javaOption == StandardSocketOptions.SO_SNDBUF) {
                    Integer value = socketChannel.getOption(StandardSocketOptions.SO_SNDBUF);
                    return value != null ? value : 0;
                }
            }
        } catch (Exception e) {
            // Fall back to manual storage
        }

        // Fall back to manual storage for unsupported options
        String key = level + ":" + optname;
        return socketOptions.getOrDefault(key, 0);
    }

    /**
     * Maps Perl socket option constants to Java StandardSocketOptions.
     * This enables native Java socket option handling with IPv4/IPv6 support.
     *
     * @param level   the protocol level
     * @param optname the option name
     * @return the corresponding Java SocketOption, or null if not supported
     */
    private SocketOption<?> mapToJavaSocketOption(int level, int optname) {
        // SOL_SOCKET = 1
        if (level == 1) {
            switch (optname) {
                case 2:  // SO_REUSEADDR
                    return StandardSocketOptions.SO_REUSEADDR;
                case 9:  // SO_KEEPALIVE
                    return StandardSocketOptions.SO_KEEPALIVE;
                case 8:  // SO_RCVBUF
                    return StandardSocketOptions.SO_RCVBUF;
                case 7:  // SO_SNDBUF
                    return StandardSocketOptions.SO_SNDBUF;
            }
        }
        // IPPROTO_TCP = 6
        else if (level == 6) {
            switch (optname) {
                case 1:  // TCP_NODELAY
                    return StandardSocketOptions.TCP_NODELAY;
            }
        }
        return null;
    }
}
