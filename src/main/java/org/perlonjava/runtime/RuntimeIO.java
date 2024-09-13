package org.perlonjava.runtime;

/*
    Additional Features

    Handling pipes (e.g., |- or -| modes).
    Handling in-memory file operations with ByteArrayInputStream or ByteArrayOutputStream.
    Implementing modes for read/write (+<, +>) operations.
 */

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

public class RuntimeIO implements RuntimeScalarReference {

    private static final int BUFFER_SIZE = 8192;
    private static final Map<String, Set<StandardOpenOption>> MODE_OPTIONS = new HashMap<>();

    static {
        MODE_OPTIONS.put("<", EnumSet.of(StandardOpenOption.READ));
        MODE_OPTIONS.put(">", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        MODE_OPTIONS.put(">>", EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        MODE_OPTIONS.put("+<", EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedReader bufferedReader;
    private boolean isEOF;
    private final ByteBuffer buffer;
    private final ByteBuffer readBuffer;
    private final ByteBuffer singleCharBuffer;
    private FileChannel fileChannel;
    private WritableByteChannel channel;

    public RuntimeIO() {
        this.buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.singleCharBuffer = ByteBuffer.allocate(1);
    }

    // Constructor to open the file with a specific mode
    public static RuntimeIO open(String fileName, String mode) {
        RuntimeIO fh = new RuntimeIO();
        try {
            Set<StandardOpenOption> options = fh.convertMode(mode);
            fh.fileChannel = FileChannel.open(Paths.get(fileName), options);

            if (options.contains(StandardOpenOption.READ)) {
                fh.bufferedReader = new BufferedReader(Channels.newReader(fh.fileChannel, StandardCharsets.UTF_8));
            }

            fh.isEOF = false;

            // Truncate the file if mode is '>'
            if (">".equals(mode)) {
                fh.fileChannel.truncate(0);
            }
            if (">>".equals(mode)) {
                fh.fileChannel.position(fh.fileChannel.size()); // Move to end for appending
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
                if (fd == FileDescriptor.out || fd == FileDescriptor.err) {
                    // For standard output and error, we can't use FileChannel
                    OutputStream out = (fd == FileDescriptor.out) ? System.out : System.err;
                    fh.outputStream = new BufferedOutputStream(out, BUFFER_SIZE);
                    fh.channel = Channels.newChannel(fh.outputStream);
                } else {
                    // For other output file descriptors, use FileChannel
                    fh.fileChannel = new FileOutputStream(fd).getChannel();
                }
            } else {
                // For input, use FileChannel
                fh.fileChannel = new FileInputStream(fd).getChannel();
                fh.bufferedReader = new BufferedReader(Channels.newReader(fh.fileChannel, StandardCharsets.UTF_8));
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
    public static RuntimeDataProvider systemCommand(RuntimeScalar command, int ctx) {
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

        String out = output.toString();
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList list = new RuntimeList();
            List<RuntimeBaseEntity> result = list.elements;
            int index = 0;
            String separator = getGlobalVariable("main::/").toString();
            int separatorLength = separator.length();

            if (separatorLength == 0) {
                result.add(new RuntimeScalar(out));
            } else {
                while (index < out.length()) {
                    int nextIndex = out.indexOf(separator, index);
                    if (nextIndex == -1) {
                        // Add the remaining part of the string
                        result.add(new RuntimeScalar(out.substring(index)));
                        break;
                    }
                    // Add the part including the separator
                    result.add(new RuntimeScalar(out.substring(index, nextIndex + separatorLength)));
                    index = nextIndex + separatorLength;
                }
            }
            return list;
        } else {
            return new RuntimeScalar(out);
        }
    }

    private Set<StandardOpenOption> convertMode(String mode) {
        Set<StandardOpenOption> options = MODE_OPTIONS.get(mode);
        if (options == null) {
            throw new IllegalArgumentException("Unsupported file mode: " + mode);
        }
        return new HashSet<>(options);
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

    // Method to read a single character (getc equivalent)
    public int getc() {
        try {
            if (fileChannel != null) {
                singleCharBuffer.clear();
                int bytesRead = fileChannel.read(singleCharBuffer);
                if (bytesRead == -1) {
                    isEOF = true;
                    return -1;
                }
                singleCharBuffer.flip();
                return singleCharBuffer.get() & 0xFF;
            } else if (bufferedReader != null) {
                int result = bufferedReader.read();
                if (result == -1) {
                    isEOF = true;
                }
                return result;
            } else if (inputStream != null) {
                int result = inputStream.read();
                if (result == -1) {
                    isEOF = true;
                }
                return result;
            }
            throw new IllegalStateException("No input source available");
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        return -1; // Indicating an error or EOF
    }

    // Method to read into a byte array
    public int read(byte[] buffer) {
        try {
            if (fileChannel != null) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
                int bytesRead = fileChannel.read(byteBuffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return bytesRead;
            } else if (inputStream != null) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return bytesRead;
            } else {
                throw new IllegalStateException("No input source available");
            }
        } catch (Exception e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        return -1; // Indicating an error or EOF
    }

    public RuntimeScalar readline() {
        try {
            if (fileChannel == null && bufferedReader == null) {
                throw new UnsupportedOperationException("Readline is not supported for output streams");
            }

            if (this.isEOF) {
                return null; // If EOF flag is already set, return null
            }

            String sep = getGlobalVariable("main::/").toString();  // fetch $/
            boolean hasSeparator = !sep.isEmpty();
            int separator = hasSeparator ? sep.charAt(0) : '\n';

            StringBuilder line = new StringBuilder();
            boolean foundSeparator = false;

            if (fileChannel != null) {
                while (!foundSeparator) {
                    readBuffer.clear();
                    int bytesRead = fileChannel.read(readBuffer);
                    if (bytesRead == -1) {
                        if (line.length() == 0) {
                            this.isEOF = true;
                            return new RuntimeScalar();
                        }
                        break;
                    }

                    readBuffer.flip();
                    while (readBuffer.hasRemaining() && !foundSeparator) {
                        char c = (char) readBuffer.get();
                        line.append(c);
                        if (hasSeparator && c == separator) {
                            foundSeparator = true;
                        }
                    }
                }
            } else {
                // Use existing bufferedReader implementation
                int c;
                while ((c = bufferedReader.read()) != -1) {
                    line.append((char) c);
                    if (hasSeparator && c == separator) {
                        foundSeparator = true;
                        break;
                    }
                }
                if (c == -1) {
                    this.isEOF = true;
                }
            }

            if (!foundSeparator) {
                this.isEOF = true;
            }

            return new RuntimeScalar(line.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }

    // Method to check for end-of-file (eof equivalent)
    public RuntimeScalar eof() {
        try {
            if (fileChannel != null) {
                this.isEOF = (fileChannel.position() >= fileChannel.size());
            } else if (bufferedReader != null) {
                this.isEOF = !bufferedReader.ready();
            } else if (inputStream != null) {
                this.isEOF = (inputStream.available() == 0);
            }
            // For output streams, EOF is not applicable
        } catch (IOException e) {
            System.err.println("File operation failed: " + e.getMessage());
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
        }
        return new RuntimeScalar(this.isEOF);
    }

    // Method to get the current file pointer position (tell equivalent)
    public long tell() {
        try {
            if (fileChannel != null) {
                return fileChannel.position();
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
        if (fileChannel != null) {
            try {
                fileChannel.position(pos);
                isEOF = false;
            } catch (Exception e) {
                System.err.println("File operation failed: " + e.getMessage());
                getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            }
        } else {
            throw new UnsupportedOperationException("Seek operation is not supported for standard streams");
        }
    }

    public RuntimeScalar flush() {
        try {
            if (fileChannel != null) {
                fileChannel.force(false);  // Force any updates to the file (false means don't force metadata updates)
            } else if (channel != null && channel instanceof FileChannel) {
                ((FileChannel) channel).force(false);
            } else if (outputStream != null) {
                outputStream.flush();
            }
            return new RuntimeScalar(1);  // Return 1 to indicate success, consistent with other methods
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();  // Return undef to indicate failure
        }
    }

    // Method to close the filehandle
    public RuntimeScalar close() {
        try {
            if (fileChannel != null) {
                fileChannel.force(true);  // Ensure all data is written to the file
                fileChannel.close();
                fileChannel = null;
            }
            if (channel != null) {
                if (channel instanceof FileChannel) {
                    ((FileChannel) channel).force(true);
                }
                channel.close();
                channel = null;
            }
            if (bufferedReader != null) {
                bufferedReader.close();
                bufferedReader = null;
            }
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
                outputStream = null;
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
            byte[] bytes = data.getBytes();
            if (channel != null) {
                // For standard output and error streams
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                // // Flush the outputStream if it's available
                // if (outputStream != null) {
                //     outputStream.flush();
                // }
            } else if (fileChannel != null) {
                // For file output using FileChannel
                int totalWritten = 0;
                while (totalWritten < bytes.length) {
                    int bytesWritten = fileChannel.write(ByteBuffer.wrap(bytes, totalWritten, bytes.length - totalWritten));
                    if (bytesWritten == 0) break; // Shouldn't happen, but just in case
                    totalWritten += bytesWritten;
                }
            } else {
                throw new IllegalStateException("No output channel available");
            }
            return new RuntimeScalar(1);
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return new RuntimeScalar();
        }
    }


}

