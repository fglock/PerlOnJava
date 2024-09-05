package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import javax.management.RuntimeErrorException;
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
    public static RuntimeIO open(String fileName, String mode) {
        RuntimeIO fh = new RuntimeIO();
        try {
            String javaMode = fh.convertMode(mode);
            fh.file = new RandomAccessFile(fileName, javaMode);
            fh.bufferedReader = new BufferedReader(new FileReader(fh.file.getFD()));
            fh.isEOF = false;

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                fh.file.setLength(0);
            }
            if (">>".equals(mode)) {
                fh.file.seek(fh.file.length()); // Move to end for appending
            }
        } catch (IOException e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            fh = null;
        }
        return fh;
    }

    // Constructor for standard output and error streams
    public static RuntimeIO open(FileDescriptor fd, boolean isOutput) {
        RuntimeIO fh = new RuntimeIO();
        try {
            if (isOutput) {
                if (fd == FileDescriptor.out) {
                    fh.outputStream = System.out;
                } else if (fd == FileDescriptor.err) {
                    fh.outputStream = System.err;
                } else {
                    fh.outputStream = new FileOutputStream(fd);
                }
            } else {
                fh.inputStream = new FileInputStream(fd);
                fh.bufferedReader = new BufferedReader(new InputStreamReader(fh.inputStream));
            }
            fh.isEOF = false;
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            fh = null;
        }
        return fh;
    }

    /**
     * Executes a shell command and captures both standard output and standard error.
     *
     * @param command The command to execute.
     * @return The output of the command as a string, including both stdout and stderr.
     */
    public static RuntimeScalar systemCommand(RuntimeScalar command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;

        try {
            // Execute the command
            process = Runtime.getRuntime().exec(command.toString());

            // Capture standard output
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Capture standard error
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Wait for the process to finish
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error: " + e.getMessage());
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (errorReader != null) {
                    errorReader.close();
                }
            } catch (IOException e) {
                throw new RuntimeException("Error closing stream: " + e.getMessage());
            }
            if (process != null) {
                process.destroy();
            }
        }

        return new RuntimeScalar(output.toString());
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

            if (this.isEOF) {
                return null; // If EOF flag is already set, return null
            }

            String sep = getGlobalVariable("main::/").toString();  // fetch $/
            boolean hasSeparator = !sep.isEmpty();
            int separator = hasSeparator ? sep.charAt(0) : 0;

            StringBuilder line = new StringBuilder();
            int c;

            while ((c = bufferedReader.read()) != -1) {
                line.append((char) c);
                if (hasSeparator && c == separator) {
                    break;
                }
            }

            if (c == -1 && line.length() == 0) {
                this.isEOF = true; // Set EOF flag when end of stream is reached
                return new RuntimeScalar();
            }

            if (c == -1) {
                this.isEOF = true; // Set EOF flag if the last line does not end with a newline
            }

            return new RuntimeScalar(line.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }

    // Method to check for end-of-file (eof equivalent)
    public RuntimeScalar eof() {
        return new RuntimeScalar(this.isEOF);
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

