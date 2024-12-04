package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

public interface IOHandle {
    RuntimeScalar read(byte[] buffer);

    RuntimeScalar write(byte[] data);

    RuntimeScalar close();

    RuntimeScalar eof();

    long tell();

    RuntimeScalar flush();

    RuntimeScalar fileno();

    // Socket-specific methods
    RuntimeScalar bind(String address, int port);

    RuntimeScalar connect(String address, int port);

    RuntimeScalar listen(int backlog);

    RuntimeScalar accept();

    RuntimeScalar getc();

    RuntimeScalar seek(long pos);

    RuntimeScalar truncate(long length);

}
