import java.io.Console;
import java.io.PrintStream;
import java.io.InputStream;

public class TTYCheck {

    // Check if standard output is a TTY
    public static boolean isStdOutTTY() {
        return System.console() != null;
    }

    // Check if an arbitrary InputStream is a TTY
    public static boolean isInputStreamTTY(InputStream input) {
        return input.equals(System.in) && System.console() != null;
    }

    // Check if an arbitrary PrintStream is a TTY
    public static boolean isPrintStreamTTY(PrintStream output) {
        return output.equals(System.out) && System.console() != null;
    }

    public static void main(String[] args) {
        System.out.println("Is standard output a TTY? " + isStdOutTTY());
        System.out.println("Is System.in a TTY? " + isInputStreamTTY(System.in));
        System.out.println("Is System.out a TTY? " + isPrintStreamTTY(System.out));
    }
}

