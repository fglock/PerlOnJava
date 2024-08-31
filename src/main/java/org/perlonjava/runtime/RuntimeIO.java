package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import java.io.*;

public class RuntimeIO implements RuntimeScalarReference {

    private RandomAccessFile file;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isEOF;

    public RuntimeIO() {
    }

    // Constructor to open the file with a specific mode
    public RuntimeIO(String fileName, String mode) throws FileNotFoundException {
        String javaMode = convertMode(mode);
        this.file = new RandomAccessFile(fileName, javaMode);
        this.isEOF = false;

        // Truncate the file if mode is '>'
        if (">".equals(mode)) {
            try {
                file.setLength(0);
            } catch (IOException e) {
                System.err.println("File operation failed: " + e.getMessage());
            }
        }
    }

    // Constructor for standard output and error streams
    public RuntimeIO(FileDescriptor fd, boolean isOutput) throws IOException {
        if (isOutput) {
            this.outputStream = new FileOutputStream(fd);
        } else {
            this.inputStream = new FileInputStream(fd);
        }
        this.isEOF = false;
    }

    public static void main(String[] args) {
        try {
            // Example usage of FileHandle

            // Writing to a file
            RuntimeIO fhWrite = new RuntimeIO("output.txt", ">");
            fhWrite.write("This line gets written into output.txt.\n");
            fhWrite.close();

            // Reading from a file
            RuntimeIO fhRead = new RuntimeIO("output.txt", "<");
            byte[] buffer = new byte[128];
            int bytesRead;
            while ((bytesRead = fhRead.read(buffer)) != -1) {
                System.out.print(new String(buffer, 0, bytesRead));
            }
            fhRead.close();

            // Example with getc and tell
            RuntimeIO fhGetc = new RuntimeIO("output.txt", "<");
            int ch;
            while ((ch = fhGetc.getc()) != -1) {
                System.out.print((char) ch);
                System.out.println(" at position: " + fhGetc.tell());
            }
            fhGetc.close();

        } catch (IOException e) {
            System.err.println("File operation failed: " + e.getMessage());
        }

        try {
            // Fetching standard handles
            RuntimeIO stdout = new RuntimeIO(FileDescriptor.out, true);
            RuntimeIO stderr = new RuntimeIO(FileDescriptor.err, true);
            RuntimeIO stdin = new RuntimeIO(FileDescriptor.in, false);

            // Writing to STDOUT
            stdout.write("This goes to STDOUT.\n");

            // Writing to STDERR
            stderr.write("This goes to STDERR.\n");

            // Reading from STDIN
            System.out.println("Type something and press enter: ");
            byte[] buffer = new byte[128];
            int bytesRead = stdin.read(buffer);
            String input = new String(buffer, 0, bytesRead);
            stdout.write("You typed: " + input);

            // Closing handles (not usually necessary for standard streams)
            stdout.close();
            stderr.close();
            stdin.close();

        } catch (IOException e) {
            System.err.println("File operation failed: " + e.getMessage());
        }
    }

    public String toStringRef() {
        return "IO(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    // Convert Perl mode to Java mode
    private String convertMode(String mode) {
        switch (mode) {
            case "<":
                return "r";
            case ">":
                return "rw";
            case ">>":
                return "rw";
            case "+<":
                return "rw";
            case "+>":
                return "rw";
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }

    // Method to read a single character (getc equivalent)
    public int getc() throws IOException {
        int result = file.read();
        if (result == -1) {
            isEOF = true;
        }
        return result;
    }

    // Method to read into a byte array
    public int read(byte[] buffer) throws IOException {
        int bytesRead = file.read(buffer);
        if (bytesRead == -1) {
            isEOF = true;
        }
        return bytesRead;
    }

    // Method to check for end-of-file (eof equivalent)
    public boolean eof() {
        return isEOF;
    }

    // Method to get the current file pointer position (tell equivalent)
    public long tell() throws IOException {
        if (file != null) {
            return file.getFilePointer();
        } else {
            throw new UnsupportedOperationException("Tell operation is not supported for standard streams");
        }
    }

    // Method to move the file pointer (seek equivalent)
    public void seek(long pos) throws IOException {
        if (file != null) {
            file.seek(pos);
            isEOF = false;
        } else {
            throw new UnsupportedOperationException("Seek operation is not supported for standard streams");
        }
    }

    // Method to close the filehandle
    public void close() throws IOException {
        if (file != null) {
            file.close();
        }
        if (inputStream != null) {
            inputStream.close();
        }
        if (outputStream != null) {
            outputStream.close();
        }
    }

    // Method to append data to a file
    public void write(String data) throws IOException {
        file.seek(file.length()); // Move to end for appending
        file.write(data.getBytes());
    }

}

