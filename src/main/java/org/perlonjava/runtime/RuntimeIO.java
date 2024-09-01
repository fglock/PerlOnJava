package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import java.io.*;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

public class RuntimeIO implements RuntimeScalarReference {

    private RandomAccessFile file;
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedReader bufferedReader;
    private boolean isEOF;

    public RuntimeIO() {
    }

    // Constructor to open the file with a specific mode
    public RuntimeIO(String fileName, String mode) {
        try {
            String javaMode = convertMode(mode);
            this.file = new RandomAccessFile(fileName, javaMode);
            this.bufferedReader = new BufferedReader(new FileReader(file.getFD()));
            this.isEOF = false;

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                file.setLength(0);
            }
            if (">>".equals(mode)) {
                file.seek(file.length()); // Move to end for appending
            }
        } catch (IOException e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
    }

    // Constructor for standard output and error streams
    public RuntimeIO(FileDescriptor fd, boolean isOutput) {
        try {
            if (isOutput) {
                if (fd == FileDescriptor.out) {
                    this.outputStream = System.out;
                } else if (fd == FileDescriptor.err) {
                    this.outputStream = System.err;
                } else {
                    this.outputStream = new FileOutputStream(fd);
                }
            } else {
                this.inputStream = new FileInputStream(fd);
                this.bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            }
            this.isEOF = false;
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
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
        buffer = new byte[128];
        bytesRead = stdin.read(buffer);
        String input = new String(buffer, 0, bytesRead);
        stdout.write("You typed: " + input);

        // Closing handles (not usually necessary for standard streams)
        stdout.close();
        stderr.close();
        stdin.close();
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
    public int getc() {
        try {
            int result = file.read();
            if (result == -1) {
                isEOF = true;
            }
            return result;
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        // TODO return false
        return 0;
    }

    // Method to read into a byte array
    public int read(byte[] buffer) {
        try {
            int bytesRead = file.read(buffer);
            if (bytesRead == -1) {
                isEOF = true;
            }
            return bytesRead;
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        // TODO return false
        return 0;
    }

    // Method to read a line from a file or input stream
    public RuntimeScalar readline() {
        try {
            if (bufferedReader == null) {
                throw new UnsupportedOperationException("Readline is not supported for output streams");
            }
            return new RuntimeScalar(bufferedReader.readLine());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }

    // Method to check for end-of-file (eof equivalent)
    public boolean eof() {
        return isEOF;
    }

    // Method to get the current file pointer position (tell equivalent)
    public long tell() {
        try {
            if (file != null) {
                return file.getFilePointer();
            } else {
                throw new UnsupportedOperationException("Tell operation is not supported for standard streams");
            }
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        // TODO return error (false)
        return 0;
    }

    // Method to move the file pointer (seek equivalent)
    public void seek(long pos) {
        if (file != null) {
            try {
                file.seek(pos);
                isEOF = false;
            } catch (Exception e) {
                System.err.println("File operation failed: " + e.getMessage());
                getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            }
        } else {
            throw new UnsupportedOperationException("Seek operation is not supported for standard streams");
        }
    }

    // Method to close the filehandle
    public RuntimeScalar close() {
        try {
            if (file != null) {
                file.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            return new RuntimeScalar(1);
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }

    // Method to append data to a file
    public RuntimeScalar write(String data) {
        try {
            if (outputStream != null) {
                outputStream.write(data.getBytes());
            } else {
                file.write(data.getBytes());
            }
            return new RuntimeScalar(1);
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }
}

