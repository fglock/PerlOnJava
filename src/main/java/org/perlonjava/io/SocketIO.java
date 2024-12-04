package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * The SocketIO class provides a simplified interface for socket operations,
 * supporting both client and server socket functionalities. It allows for
 * binding, connecting, listening, accepting connections, and reading/writing
 * data over sockets.
 */
public class SocketIO implements IOHandle {
    private Socket socket;
    private ServerSocket serverSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;

    /**
     * Constructs a SocketIO instance for a client socket.
     *
     * @param socket the client socket to be used for communication
     */
    public SocketIO(Socket socket) {
        this.socket = socket;
        try {
            if (this.socket != null) {
                this.inputStream = this.socket.getInputStream();
                this.outputStream = this.socket.getOutputStream();
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

    /**
     * Reads data from the socket into the provided buffer.
     *
     * @param buffer the buffer to store the read data
     * @return a RuntimeScalar containing the number of bytes read, or undefined if end-of-file is reached
     */
    @Override
    public RuntimeScalar read(byte[] buffer) {
        try {
            if (inputStream != null) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                    return scalarUndef;
                }
                return new RuntimeScalar(bytesRead);
            }
            throw new IllegalStateException("No input stream available");
        } catch (IOException e) {
            return handleIOException(e, "read operation failed");
        }
    }

    /**
     * Writes data to the socket.
     *
     * @param data the data to be written to the socket
     * @return a RuntimeScalar indicating success (true) or failure (false)
     */
    @Override
    public RuntimeScalar write(byte[] data) {
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
     * Returns the current position in the input stream.
     *
     * @return the current position in the input stream
     */
    @Override
    public long tell() {
        throw new UnsupportedOperationException("tell operation is not supported for sockets");
    }

    /**
     * Sets the position in the input stream.
     *
     * @param pos the position to set
     * @return
     */
    @Override
    public RuntimeScalar seek(long pos) {
        throw new UnsupportedOperationException("seek operation is not supported for sockets");
    }

    /**
     * Reads a single byte from the socket's input stream.
     *
     * @return a RuntimeScalar containing the byte read, or undefined if end-of-file is reached
     */
    public RuntimeScalar getc() {
        try {
            if (inputStream != null) {
                int byteRead = inputStream.read();
                if (byteRead == -1) {
                    isEOF = true;
                    return scalarUndef;
                }
                return new RuntimeScalar(byteRead);
            }
            throw new IllegalStateException("No input stream available");
        } catch (IOException e) {
            return handleIOException(e, "getc operation failed");
        }
    }

    public RuntimeScalar truncate(long length) {
        throw new UnsupportedOperationException("Truncate operation is not supported.");
    }

}
