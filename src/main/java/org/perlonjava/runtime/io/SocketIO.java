package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.ErrnoVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

/**
 * The SocketIO class provides a simplified interface for socket operations,
 * supporting both client and server socket functionalities. It allows for
 * binding, connecting, listening, accepting connections, and reading/writing
 * data over sockets.
 */
public class SocketIO implements IOHandle {
    // Socket options storage: key is "level:optname", value is the option value
    private final Map<String, Integer> socketOptions;
    private Socket socket;
    private ServerSocket serverSocket;
    private SocketChannel socketChannel;
    private ServerSocketChannel serverSocketChannel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;
    private boolean blocking = true;
    private CharsetDecoderHelper decoderHelper;
    // Track the protocol family for server socket conversion in listen()
    private ProtocolFamily protocolFamily;
    // Track bound address for lazy server socket creation in listen()
    private InetSocketAddress boundAddress;
    // DatagramChannel for UDP sockets
    private DatagramChannel datagramChannel;
    // Last received sender address for recv() return value
    private SocketAddress lastReceivedFrom;

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
     * Constructs a SocketIO instance from a SocketChannel (unconnected).
     * Created by Perl's socket() builtin for SOCK_STREAM. The socket can
     * later be used with connect() (client) or bind()+listen() (server).
     *
     * @param channel the unconnected socket channel
     * @param family  the protocol family (INET, INET6, etc.)
     */
    public SocketIO(SocketChannel channel, ProtocolFamily family) {
        this.socketChannel = channel;
        this.socket = channel.socket();
        this.protocolFamily = family;
        this.socketOptions = new HashMap<>();
    }

    /**
     * Constructs a SocketIO instance from a DatagramChannel for UDP sockets.
     *
     * @param channel the datagram channel
     * @param family  the protocol family (INET, INET6, etc.)
     */
    public SocketIO(DatagramChannel channel, ProtocolFamily family) {
        this.datagramChannel = channel;
        this.protocolFamily = family;
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
            InetSocketAddress bindAddr = new InetSocketAddress(address, port);
            if (datagramChannel != null) {
                datagramChannel.bind(bindAddr);
                this.boundAddress = bindAddr;
            } else if (socket != null) {
                socket.bind(bindAddr);
                this.boundAddress = bindAddr;
            } else if (serverSocket != null) {
                serverSocket.bind(bindAddr);
                this.boundAddress = bindAddr;
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
     * Initializes input/output streams after successful connection.
     *
     * @param address the remote IP address to connect to
     * @param port    the remote port number to connect to
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    public RuntimeScalar connect(String address, int port) {
        if (socket == null && socketChannel == null) {
            throw new IllegalStateException("No socket available to connect");
        }
        try {
            InetSocketAddress target = new InetSocketAddress(address, port);

            // Use SocketChannel for non-blocking connect support
            if (socketChannel != null && !blocking) {
                boolean connected = socketChannel.connect(target);
                if (!connected) {
                    // Connection in progress — set EINPROGRESS
                    // Return undef (not false) to match Perl 5's connect() behavior.
                    // IO::Socket::IP relies on `defined connect(...)` to detect failure.
                    getGlobalVariable("main::!").set(ErrnoVariable.EINPROGRESS());
                    return scalarUndef;
                }
                // Connected immediately
                this.socket = socketChannel.socket();
                this.inputStream = socket.getInputStream();
                this.outputStream = socket.getOutputStream();
                return scalarTrue;
            }

            // Blocking connect via Socket API
            if (socket != null) {
                socket.connect(target);
            } else {
                socketChannel.connect(target);
                this.socket = socketChannel.socket();
            }
            // Initialize streams after successful connection
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
            return scalarTrue;
        } catch (IOException e) {
            // Perl 5's connect() returns undef on failure (not false).
            // IO::Socket::IP relies on `defined connect(...)` to detect failure.
            handleIOException(e, "connect operation failed");
            return scalarUndef;
        }
    }

    /**
     * Get the current blocking mode of the socket.
     *
     * @return true if blocking, false if non-blocking
     */
    public boolean isBlocking() {
        return blocking;
    }

    /**
     * Set the blocking mode of the socket.
     * Configures the underlying NIO channel for non-blocking I/O when available.
     *
     * @param newBlocking true for blocking, false for non-blocking
     */
    public void setBlocking(boolean newBlocking) {
        this.blocking = newBlocking;
        try {
            if (socketChannel != null) {
                socketChannel.configureBlocking(newBlocking);
                // When transitioning to blocking mode after a non-blocking connect,
                // finish pending connection and initialize streams if needed
                if (newBlocking && outputStream == null) {
                    if (socketChannel.isConnectionPending()) {
                        socketChannel.finishConnect();
                    }
                    if (socketChannel.isConnected()) {
                        this.socket = socketChannel.socket();
                        this.inputStream = socket.getInputStream();
                        this.outputStream = socket.getOutputStream();
                    }
                }
            }
            if (serverSocketChannel != null) {
                serverSocketChannel.configureBlocking(newBlocking);
            }
        } catch (IOException e) {
            // Silently ignore — the blocking field still tracks the desired state
        }
    }

    /**
     * Get the socket error status (for SO_ERROR getsockopt).
     * For non-blocking connects, attempts to finish the connection and
     * returns the appropriate errno (0 if connected, error code otherwise).
     *
     * @return 0 if no error, errno value otherwise
     */
    public int getSocketError() {
        if (socketChannel != null && socketChannel.isOpen()) {
            try {
                if (socketChannel.isConnectionPending()) {
                    boolean finished = socketChannel.finishConnect();
                    if (finished) {
                        // Connection completed successfully
                        this.socket = socketChannel.socket();
                        this.inputStream = socket.getInputStream();
                        this.outputStream = socket.getOutputStream();
                        return 0;
                    }
                    // Still in progress
                    return ErrnoVariable.EINPROGRESS();
                }
                if (socketChannel.isConnected()) {
                    return 0;
                }
            } catch (java.net.ConnectException e) {
                return ErrnoVariable.ECONNREFUSED();
            } catch (java.net.SocketTimeoutException e) {
                return ErrnoVariable.ETIMEDOUT();
            } catch (IOException e) {
                return 5; // EIO
            }
        }
        return 0;
    }

    /**
     * Puts the socket into listening mode. If only a client socket exists
     * (from socket() builtin), converts it to a server socket first.
     *
     * @param backlog the maximum number of pending connections
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    public RuntimeScalar listen(int backlog) {
        try {
            if (serverSocket == null) {
                // Convert from client socket to server socket.
                // Close the client socket/channel and create a ServerSocketChannel.
                InetSocketAddress addr = this.boundAddress;
                if (socketChannel != null) {
                    socketChannel.close();
                    socketChannel = null;
                }
                if (socket != null) {
                    // Don't close if we got the bound address from the socket
                    if (addr == null && socket.getLocalSocketAddress() instanceof InetSocketAddress localAddr) {
                        addr = localAddr;
                    }
                    socket.close();
                    socket = null;
                }

                // Create a new ServerSocketChannel and bind to the same address
                serverSocketChannel = ServerSocketChannel.open();
                serverSocket = serverSocketChannel.socket();

                // Apply stored SO_REUSEADDR option if set
                String reuseKey = "1:2"; // SOL_SOCKET:SO_REUSEADDR
                if (socketOptions.containsKey(reuseKey) && socketOptions.get(reuseKey) != 0) {
                    serverSocket.setReuseAddress(true);
                }

                if (addr != null) {
                    serverSocket.bind(addr, backlog);
                } else {
                    // Not yet bound - will need to bind separately
                    // Store backlog for later use
                    serverSocket.bind(null, backlog);
                }
            } else {
                // Already a server socket - if not yet bound, bind now
                if (!serverSocket.isBound()) {
                    serverSocket.bind(this.boundAddress, backlog);
                }
                // If already bound, listen is already active from bind
            }
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "listen operation failed");
            return scalarFalse;
        }
    }

    /**
     * Accepts a connection on the server socket and returns a new SocketIO
     * for the accepted client connection.
     *
     * @return the SocketIO for the accepted connection, or null on failure
     */
    public SocketIO acceptConnection() {
        if (serverSocket == null) {
            throw new IllegalStateException("No server socket available to accept connections");
        }
        try {
            // Prefer NIO channel accept — returns a SocketChannel that works with Selector
            if (serverSocketChannel != null) {
                SocketChannel clientChannel = serverSocketChannel.accept();
                if (clientChannel == null) {
                    return null; // non-blocking and no connection pending
                }
                Socket clientSocket = clientChannel.socket();
                SocketIO clientIO = new SocketIO(clientSocket);
                // Ensure the channel is set on the new SocketIO
                clientIO.socketChannel = clientChannel;
                return clientIO;
            }
            // Fallback to blocking accept
            Socket clientSocket = serverSocket.accept();
            return new SocketIO(clientSocket);
        } catch (IOException e) {
            handleIOException(e, "accept operation failed");
            return null;
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
        if (socketChannel != null) {
            return new RuntimeScalar(socketChannel.hashCode());
        }
        if (serverSocketChannel != null) {
            return new RuntimeScalar(serverSocketChannel.hashCode());
        }
        if (socket != null) {
            return new RuntimeScalar(socket.hashCode());
        }
        if (serverSocket != null) {
            return new RuntimeScalar(serverSocket.hashCode());
        }
        return scalarUndef;
    }

    /**
     * Returns the NIO SelectableChannel for use with java.nio.channels.Selector.
     * For server sockets, returns the ServerSocketChannel (selectable for OP_ACCEPT).
     * For client sockets, returns the SocketChannel (selectable for OP_READ/OP_WRITE).
     *
     * @return the SelectableChannel, or null if not available
     */
    public SelectableChannel getSelectableChannel() {
        if (serverSocketChannel != null) {
            return serverSocketChannel;
        }
        if (socketChannel != null) {
            return socketChannel;
        }
        return null;
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
            // Use channel-based I/O for non-blocking sockets to avoid
            // IllegalBlockingModeException from stream-based I/O
            if (!blocking && socketChannel != null) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
                int written = socketChannel.write(buf);
                return written > 0 ? scalarTrue : scalarFalse;
            }

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
     * Low-level read from the socket (sysread equivalent).
     * Reads raw bytes without buffering, suitable for use by HTTP::Daemon and similar.
     *
     * @param length maximum number of bytes to read
     * @return RuntimeScalar containing the bytes read, empty string at EOF, or undef on error
     */
    @Override
    public RuntimeScalar sysread(int length) {
        try {
            // Use channel-based I/O for non-blocking sockets to avoid
            // IllegalBlockingModeException from stream-based I/O
            if (!blocking && socketChannel != null) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(length);
                int bytesRead = socketChannel.read(buf);
                if (bytesRead == -1) {
                    isEOF = true;
                    return new RuntimeScalar("");
                }
                if (bytesRead == 0) {
                    // Would block — set EWOULDBLOCK
                    getGlobalVariable("main::!").set(ErrnoVariable.EAGAIN()); // EAGAIN/EWOULDBLOCK
                    return scalarUndef;
                }
                byte[] result = new byte[bytesRead];
                buf.flip();
                buf.get(result);
                return new RuntimeScalar(result);
            }

            if (inputStream != null) {
                byte[] buffer = new byte[length];
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                    return new RuntimeScalar("");
                }
                byte[] result = new byte[bytesRead];
                System.arraycopy(buffer, 0, result, 0, bytesRead);
                return new RuntimeScalar(result);
            }
            throw new IllegalStateException("No input stream available");
        } catch (IOException e) {
            return handleIOException(e, "sysread operation failed");
        }
    }

    /**
     * Low-level write to the socket (syswrite equivalent).
     * Writes raw bytes without buffering.
     *
     * @param data the data to write
     * @return RuntimeScalar containing the number of bytes written, or undef on error
     */
    @Override
    public RuntimeScalar syswrite(String data) {
        try {
            byte[] bytes = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) {
                bytes[i] = (byte) (data.charAt(i) & 0xFF);
            }

            // Use channel-based I/O for non-blocking sockets to avoid
            // IllegalBlockingModeException from stream-based I/O
            if (!blocking && socketChannel != null) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
                int written = socketChannel.write(buf);
                if (written == 0) {
                    // Would block — set EWOULDBLOCK
                    getGlobalVariable("main::!").set(ErrnoVariable.EAGAIN()); // EAGAIN/EWOULDBLOCK
                    return scalarUndef;
                }
                return new RuntimeScalar(written);
            }

            if (outputStream != null) {
                outputStream.write(bytes);
                outputStream.flush();
                return new RuntimeScalar(bytes.length);
            }
            throw new IllegalStateException("No output stream available");
        } catch (IOException e) {
            return handleIOException(e, "syswrite operation failed");
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

            if (datagramChannel != null && datagramChannel.getLocalAddress() instanceof InetSocketAddress) {
                localAddress = (InetSocketAddress) datagramChannel.getLocalAddress();
            } else if (socket != null && socket.getLocalSocketAddress() instanceof InetSocketAddress) {
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
     * Check if this is a datagram (UDP) socket.
     */
    public boolean isDatagramSocket() {
        return datagramChannel != null;
    }

    /**
     * Send a datagram to a specific address.
     *
     * @param data    the data to send
     * @param target  the destination address
     * @return number of bytes sent, or -1 on error
     */
    public int sendTo(byte[] data, SocketAddress target) throws IOException {
        if (datagramChannel == null) {
            throw new IllegalStateException("Not a datagram socket");
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
        return datagramChannel.send(buf, target);
    }

    /**
     * Receive a datagram. Stores the sender address accessible via getLastReceivedFrom().
     *
     * @param maxLength maximum number of bytes to receive
     * @return the received data as a byte array, or null on error
     */
    public byte[] recvFrom(int maxLength) throws IOException {
        if (datagramChannel == null) {
            throw new IllegalStateException("Not a datagram socket");
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(maxLength);
        lastReceivedFrom = datagramChannel.receive(buf);
        if (lastReceivedFrom == null) {
            return null;
        }
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    /**
     * Get the sender address from the last recvFrom() call.
     * Returns a packed sockaddr_in structure suitable for Perl.
     */
    public RuntimeScalar getLastReceivedFrom() {
        if (lastReceivedFrom instanceof InetSocketAddress addr) {
            return packSockaddrIn(addr);
        }
        return scalarUndef;
    }

    /**
     * Get the DatagramChannel for select() support.
     */
    public DatagramChannel getDatagramChannel() {
        return datagramChannel;
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
