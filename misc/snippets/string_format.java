import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuntimeScalar {

    public static String dynamicFormat(String format, Object... args) {
        List<Object> formattedArgs = new ArrayList<>();

        // Regex to match format specifiers with optional flags, width, precision
        Pattern pattern = Pattern.compile("%[\\d\\-.]*[a-zA-Z]");
        Matcher matcher = pattern.matcher(format);

        int argIndex = 0;
        while (matcher.find()) {
            String specifier = matcher.group();
            Object arg = args[argIndex++];

            char type = specifier.charAt(specifier.length() - 1);

            switch (type) {
                case 'd':
                case 'i':
                    formattedArgs.add(convertToInt(arg));
                    break;
                case 'f':
                case 'e':
                case 'g':
                    formattedArgs.add(convertToDouble(arg));
                    break;
                case 's':
                    formattedArgs.add(arg.toString());
                    break;
                case 'b':
                    formattedArgs.add(convertToBoolean(arg));
                    break;
                case 'c':
                    formattedArgs.add(convertToChar(arg));
                    break;
                default:
                    formattedArgs.add(arg);
                    break;
            }
        }

        // Use the transformed arguments with String.format
        return String.format(format, formattedArgs.toArray());
    }

    private static int convertToInt(Object arg) {
        if (arg instanceof Number) {
            return ((Number) arg).intValue();
        } else if (arg instanceof String) {
            return Integer.parseInt((String) arg);
        } else {
            throw new IllegalArgumentException("Cannot convert to int: " + arg);
        }
    }

    private static double convertToDouble(Object arg) {
        if (arg instanceof Number) {
            return ((Number) arg).doubleValue();
        } else if (arg instanceof String) {
            return Double.parseDouble((String) arg);
        } else {
            throw new IllegalArgumentException("Cannot convert to double: " + arg);
        }
    }

    private static boolean convertToBoolean(Object arg) {
        if (arg instanceof Boolean) {
            return (Boolean) arg;
        } else if (arg instanceof String) {
            return Boolean.parseBoolean((String) arg);
        } else {
            throw new IllegalArgumentException("Cannot convert to boolean: " + arg);
        }
    }

    private static char convertToChar(Object arg) {
        if (arg instanceof Character) {
            return (Character) arg;
        } else if (arg instanceof String && ((String) arg).length() == 1) {
            return ((String) arg).charAt(0);
        } else {
            throw new IllegalArgumentException("Cannot convert to char: " + arg);
        }
    }

    public static void main(String[] args) {
        RuntimeScalar scalar = new RuntimeScalar();
        String result = scalar.dynamicFormat("ID: %05d, Value: %8.2f, Name: %s, Active: %b", "123", "45.6789", "Widget", "true");
        System.out.println(result); // Outputs: ID: 00123, Value:    45.68, Name: Widget, Active: true
    }
}

