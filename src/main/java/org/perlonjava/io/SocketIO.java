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

public class SocketIO {
    private Socket socket;
    private ServerSocket serverSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;

    // Constructor for client socket
    public SocketIO(Socket socket) {
        this.socket = socket;
        initializeStreams();
    }

    // Constructor for server socket
    public SocketIO(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    private void initializeStreams() {
        try {
            if (socket != null) {
                this.inputStream = socket.getInputStream();
                this.outputStream = socket.getOutputStream();
            }
        } catch (IOException e) {
            handleIOException(e, "Failed to initialize socket streams");
        }
    }

    // Method to bind a socket
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
            handleIOException(e, "bind operation failed");
            return scalarFalse;
        }
    }

    // Method to connect a socket
    public RuntimeScalar connect(String address, int port) {
        if (socket == null) {
            throw new IllegalStateException("No socket available to connect");
        }
        try {
            socket.connect(new InetSocketAddress(address, port));
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "connect operation failed");
            return scalarFalse;
        }
    }

    // Method to listen on a server socket
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

    // Method to accept a connection on a server socket
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

    // Method to emulate Perl's fileno function
    public RuntimeScalar fileno() {
        if (socket != null) {
            return new RuntimeScalar(socket.getChannel().hashCode());
        }
        return scalarUndef;
    }

    // Method to read data from the socket
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
            handleIOException(e, "read operation failed");
            return scalarUndef;
        }
    }

    // Method to write data to the socket
    public RuntimeScalar write(byte[] data) {
        try {
            if (outputStream != null) {
                outputStream.write(data);
                return scalarTrue;
            }
            throw new IllegalStateException("No output stream available");
        } catch (IOException e) {
            handleIOException(e, "write operation failed");
            return scalarFalse;
        }
    }

    // Method to flush the output stream
    public RuntimeScalar flush() {
        try {
            if (outputStream != null) {
                outputStream.flush();
                return scalarTrue;
            }
            return scalarFalse;
        } catch (IOException e) {
            handleIOException(e, "flush operation failed");
            return scalarFalse;
        }
    }

    // Method to check for end-of-file
    public RuntimeScalar eof() {
        return isEOF ? scalarTrue : scalarFalse;
    }

    // Method to close the socket or server socket
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
            handleIOException(e, "close operation failed");
            return scalarFalse;
        }
    }
}
