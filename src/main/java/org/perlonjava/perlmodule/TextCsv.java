package org.perlonjava.perlmodule;

import org.apache.commons.csv.*;
import org.perlonjava.operators.Readline;
import org.perlonjava.runtime.*;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.JAVAOBJECT;

/**
 * Text::CSV module implementation for PerlOnJava.
 * This class provides CSV parsing and generation using Apache Commons CSV.
 */
public class TextCsv extends PerlModuleBase {

    // Error codes matching Perl's Text::CSV
    private static final int INI_SEPARATOR_CONFLICT = 1001;
    private static final int EIF_LOOSE_UNESCAPED_QUOTE = 2034;
    private static final int EIQ_QUOTED_FIELD_NOT_TERMINATED = 2027;
    private static final int ECB_BINARY_CHARACTER = 2110;

    private static final String cacheKey = "_CSVFormat";

    /**
     * Constructor initializes the Text::CSV module.
     */
    public TextCsv() {
        super("Text::CSV", false);
    }

    /**
     * Initializes and registers all Text::CSV methods.
     */
    public static void initialize() {
        TextCsv csv = new TextCsv();
        try {
            // Register all supported Text::CSV methods
            csv.registerMethod("parse", null);
            csv.registerMethod("fields", null);
            csv.registerMethod("combine", null);
            csv.registerMethod("string", null);
            csv.registerMethod("getline", null);
            csv.registerMethod("error_diag", null);
            csv.registerMethod("getline_hr", null);
            csv.registerMethod("eol", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Text::CSV method: " + e.getMessage());
        }
    }

    /**
     * Parse a CSV line.
     */
    public static RuntimeList parse(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar line = args.get(1);
        RuntimeArray fields = new RuntimeArray();

        if (line.toString().isEmpty()) {
            // Perl Text::CSV treats an empty input string ("") as a single empty field
            RuntimeArray.push(fields, scalarEmptyString);
            self.put("_fields", fields.createReference());
            self.put("_string", line);
            clearError(self);
            return scalarTrue.getList();
        }

        try {
            // Build CSV format from attributes
            CSVFormat format = buildCSVFormat(self);

            // Parse the line
            CSVParser parser = CSVParser.parse(line.toString(), format);
            List<CSVRecord> records = parser.getRecords();

            if (!records.isEmpty()) {
                CSVRecord record = records.get(0);

                int fieldIndex = 0;
                for (String field : record) {
                    RuntimeScalar value = new RuntimeScalar(field);

                    // Handle blank_is_undef
                    if (self.get("blank_is_undef").getBoolean() && field.isEmpty()) {
                        value = scalarUndef;
                    }

                    // Handle empty_is_undef
                    if (self.get("empty_is_undef").getBoolean() && field.isEmpty()) {
                        value = scalarUndef;
                    }

                    // Fixed to use static push method
                    RuntimeArray.push(fields, value);
                    fieldIndex++;
                }

                self.put("_fields", fields.createReference());
                self.put("_string", line);
                clearError(self);
                return scalarTrue.getList();
            }

            return scalarFalse.getList();

        } catch (Exception e) {
            setError(self, EIQ_QUOTED_FIELD_NOT_TERMINATED, e.getMessage(), 0, 0);
            return scalarFalse.getList();
        }
    }

    /**
     * Get parsed fields.
     */
    public static RuntimeList fields(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fieldsRef = self.get("_fields");

        if (fieldsRef != null && fieldsRef.type == RuntimeScalarType.ARRAYREFERENCE) {
            return fieldsRef.arrayDeref().getList();
        }

        return new RuntimeList();
    }

    /**
     * Combine fields into a CSV string.
     */
    public static RuntimeList combine(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            // Build CSV format
            CSVFormat format = buildCSVFormat(self);

            // Get fields from arguments
            List<String> values = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                RuntimeScalar field = args.get(i);
                // Fixed to check type instead of isUndef()
                if (field.type == RuntimeScalarType.UNDEF) {
                    values.add("");
                } else {
                    values.add(field.toString());
                }
            }

            // Generate CSV string
            StringWriter sw = new StringWriter();
            CSVPrinter printer = new CSVPrinter(sw, format);
            printer.printRecord(values);
            printer.flush();

            String csvString = sw.toString().trim(); // Remove trailing newline

            self.put("_string", new RuntimeScalar(csvString));
            clearError(self);
            return scalarTrue.getList();

        } catch (Exception e) {
            setError(self, ECB_BINARY_CHARACTER, e.getMessage(), 0, 0);
            return scalarFalse.getList();
        }
    }

    /**
     * Get the combined CSV string.
     */
    public static RuntimeList string(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar str = self.get("_string");

        if (str != null) {
            return str.getList();
        }

        return scalarUndef.getList();
    }

    /**
     * Get/set eol attribute.
     */
    public static RuntimeList eol(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();

        if (args.size() > 1) {
            // Setter
            RuntimeScalar eol = args.get(1);
            self.put("eol", eol);
            // Invalidate cache
            self.delete(cacheKey);
        }

        // Getter
        RuntimeScalar eol = self.get("eol");
        return eol != null ? eol.getList() : scalarUndef.getList();
    }

    /**
     * Parse a line from a filehandle.
     */
    public static RuntimeList getline(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fh = args.get(1);

        // Read a line from the filehandle
        RuntimeArray readArgs = new RuntimeArray();
        RuntimeArray.push(readArgs, fh);
        RuntimeScalar line = Readline.readline(fh.getRuntimeIO());

        // Fixed to check type instead of isUndef()
        if (line.type == RuntimeScalarType.UNDEF) {
            return scalarUndef.getList();
        }

        // Parse the line
        RuntimeArray parseArgs = new RuntimeArray();
        RuntimeArray.push(parseArgs, args.get(0));
        RuntimeArray.push(parseArgs, line.getFirst());

        RuntimeList result = parse(parseArgs, ctx);
        if (result.getFirst().getBoolean()) {
            return self.get("_fields").getList();
        }

        return scalarUndef.getList();
    }

    /**
     * Parse a line and return as hashref using column names.
     */
    public static RuntimeList getline_hr(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        // Check if column names are set
        RuntimeScalar colNamesRef = self.get("column_names");
        if (colNamesRef.type == RuntimeScalarType.UNDEF || colNamesRef.arrayDeref().size() == 0) {
            setError(self, 3002, "getline_hr() called before column_names()", 0, 0);
            return scalarUndef.getList();
        }

        // Get a line
        RuntimeList lineResult = getline(args, ctx);
        if (lineResult.isEmpty() || lineResult.getFirst().type == RuntimeScalarType.UNDEF) {
            return scalarUndef.getList();
        }

        // Convert to hash
        RuntimeArray fields = lineResult.getFirst().arrayDeref();
        RuntimeArray colNames = colNamesRef.arrayDeref();
        RuntimeHash hash = new RuntimeHash();

        for (int i = 0; i < colNames.size() && i < fields.size(); i++) {
            hash.put(colNames.get(i).toString(), fields.get(i));
        }

        return hash.createReference().getList();
    }

    /**
     * Get error diagnostics.
     */
    public static RuntimeList error_diag(RuntimeArray args, int ctx) {
        RuntimeHash self = null;

        if (args.size() > 0 && args.get(0).type == RuntimeScalarType.HASHREFERENCE) {
            self = args.get(0).hashDeref();
        }

        if (self == null) {
            // Class method call - return last global error
            return new RuntimeScalar("").getList();
        }

        // Instance method call
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList result = new RuntimeList();
            result.add(self.get("_ERROR_CODE"));
            result.add(self.get("_ERROR_STR"));
            result.add(self.get("_ERROR_POS"));
            result.add(scalarZero); // record number
            result.add(self.get("_ERROR_FIELD"));
            return result;
        } else {
            // Scalar context - return error string
            return self.get("_ERROR_STR").getList();
        }
    }

    /**
     * Build CSVFormat from attributes.
     */
    private static CSVFormat buildCSVFormat(RuntimeHash self) {
        RuntimeScalar cached = self.get(cacheKey);
        if (cached != null && cached.type == JAVAOBJECT) {
            return (CSVFormat) cached.value;
        }

        // Start with RFC4180 format which handles quote doubling correctly
        CSVFormat.Builder builder = CSVFormat.RFC4180.builder();

        // Set delimiter
        String sepChar = self.get("sep_char").toString();
        if (sepChar.length() == 1) {
            builder.setDelimiter(sepChar.charAt(0));
        }

        // Set quote character
        RuntimeScalar quoteChar = self.get("quote_char");
        if (quoteChar.type != RuntimeScalarType.UNDEF && quoteChar.toString().length() == 1) {
            builder.setQuote(quoteChar.toString().charAt(0));
        } else if (quoteChar.type == RuntimeScalarType.UNDEF) {
            builder.setQuote(null);
        }

        // Handle escape character properly
        RuntimeScalar escapeChar = self.get("escape_char");

        // In standard CSV, when escape_char is undef, we should NOT set an escape character
        // This allows quote doubling to work properly
        if (escapeChar.type == RuntimeScalarType.UNDEF) {
            builder.setEscape(null);
        } else {
            // Check if escape_char was explicitly set to something different from quote_char
            String escapeStr = escapeChar.toString();
            String quoteStr = quoteChar.toString();

            if (!escapeStr.equals(quoteStr) && escapeStr.length() == 1) {
                builder.setEscape(escapeStr.charAt(0));
            } else {
                // If escape_char equals quote_char or is empty, use quote doubling
                builder.setEscape(null);
            }
        }

        // Handle other options
        if (self.get("allow_whitespace").getBoolean()) {
            builder.setIgnoreSurroundingSpaces(true);
        }

        if (self.get("always_quote").getBoolean()) {
            builder.setQuoteMode(QuoteMode.ALL);
        }

        // Don't set record separator for parsing single lines
        builder.setRecordSeparator(null);

        CSVFormat csvFormat = builder.build();

        // Cache the format
        if (cached == null) {
            cached = new RuntimeScalar();
            self.put(cacheKey, cached);
        }
        cached.set(csvFormat);

        return csvFormat;
    }


    /**
     * Set error information.
     */
    private static void setError(RuntimeHash self, int code, String message, int pos, int field) {
        self.put("_ERROR_CODE", new RuntimeScalar(code));
        self.put("_ERROR_STR", new RuntimeScalar(message));
        self.put("_ERROR_POS", new RuntimeScalar(pos));
        self.put("_ERROR_FIELD", new RuntimeScalar(field));

        // Handle auto_diag
        if (self.get("auto_diag").getBoolean()) {
            System.err.println("# CSV ERROR: " + code + " - " + message);
        }
    }

    /**
     * Clear error state.
     */
    private static void clearError(RuntimeHash self) {
        self.put("_ERROR_CODE", scalarZero);
        self.put("_ERROR_STR", new RuntimeScalar(""));
        self.put("_ERROR_POS", scalarZero);
        self.put("_ERROR_FIELD", scalarZero);
    }
}
