package org.perlonjava.perlmodule;

import org.perlonjava.operators.Operator;
import org.perlonjava.operators.Readline;
import org.perlonjava.runtime.*;
import org.perlonjava.operators.ReferenceOperators;
import org.apache.commons.csv.*;
import java.io.*;
import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.*;

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
            csv.registerMethod("print", null);
            csv.registerMethod("getline", null);
            csv.registerMethod("error_diag", null);
            csv.registerMethod("sep_char", null);
            csv.registerMethod("quote_char", null);
            // csv.registerMethod("escape_char", null);
            // csv.registerMethod("binary", null);
            // csv.registerMethod("eol", null);
            // csv.registerMethod("always_quote", null);
            csv.registerMethod("column_names", null);
            csv.registerMethod("getline_hr", null);
            // csv.registerMethod("header", null);
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

            String csvString = sw.toString();
            // Remove trailing newline if no eol set
            if (self.get("eol").type == RuntimeScalarType.UNDEF && csvString.endsWith("\n")) {
                csvString = csvString.substring(0, csvString.length() - 1);
            }

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
     * Print fields to a filehandle.
     */
    public static RuntimeList print(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fh = args.get(1);
        RuntimeScalar fieldsRef = args.get(2);

        if (fieldsRef.type != RuntimeScalarType.ARRAYREFERENCE) {
            return scalarFalse.getList();
        }

        // Combine the fields
        RuntimeArray combineArgs = new RuntimeArray();
        RuntimeArray.push(combineArgs, args.get(0));
        for (RuntimeScalar field : fieldsRef.arrayDeref().elements) {
            RuntimeArray.push(combineArgs, field);
        }

        RuntimeList combineResult = combine(combineArgs, ctx);
        if (!combineResult.getFirst().getBoolean()) {
            return scalarFalse.getList();
        }

        // Print to filehandle
        String output = self.get("_string").toString();
        RuntimeScalar eol = self.get("eol");
        if (eol.type != RuntimeScalarType.UNDEF) {
            output += eol.toString();
        }

        RuntimeArray printArgs = new RuntimeArray();
        RuntimeArray.push(printArgs, new RuntimeScalar(output));
        Operator.print(printArgs.getList(), fh);

        return scalarTrue.getList();
    }

    /**
     * Get/set separator character.
     */
    public static RuntimeList sep_char(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();

        if (args.size() > 1) {
            RuntimeScalar sep = args.get(1);
            if (sep.type != RuntimeScalarType.UNDEF && sep.toString().length() == 1) {
                self.put("sep_char", sep);
            }
        }

        return self.get("sep_char").getList();
    }

    /**
     * Get/set quote character.
     */
    public static RuntimeList quote_char(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();

        if (args.size() > 1) {
            RuntimeScalar quote = args.get(1);
            if (quote.type != RuntimeScalarType.UNDEF && quote.toString().length() == 1) {
                self.put("quote_char", quote);
            }
        }

        return self.get("quote_char").getList();
    }

    /**
     * Get/set column names.
     */
    public static RuntimeList column_names(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();

        if (args.size() > 1) {
            RuntimeArray names = new RuntimeArray();

            // Handle array reference
            if (args.get(1).type == RuntimeScalarType.ARRAYREFERENCE) {
                names = args.get(1).arrayDeref();
            } else {
                // Handle list of names
                for (int i = 1; i < args.size(); i++) {
                    RuntimeArray.push(names, args.get(i));
                }
            }

            self.put("column_names", names.createReference());
        }

        RuntimeScalar namesRef = self.get("column_names");
        if (namesRef != null && namesRef.type == RuntimeScalarType.ARRAYREFERENCE) {
            return namesRef.arrayDeref().getList();
        }

        return new RuntimeList();
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
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder();

        // builder.setSkipHeaderRecord(false);
        // builder.setAllowMissingColumnNames(true);

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

        // Set escape character
        String escapeChar = self.get("escape_char").toString();
        if (escapeChar.length() == 1) {
            builder.setEscape(escapeChar.charAt(0));
        } else {
            builder.setEscape(null);
        }

        // Handle other options
        if (self.get("allow_whitespace").getBoolean()) {
            builder.setIgnoreSurroundingSpaces(true);
        }

        if (self.get("always_quote").getBoolean()) {
            builder.setQuoteMode(QuoteMode.ALL);
        }

        // Set record separator if specified
        RuntimeScalar eol = self.get("eol");
        if (eol.type != RuntimeScalarType.UNDEF) {
            builder.setRecordSeparator(eol.toString());
        } else {
            builder.setRecordSeparator("");
        }

        return builder.build();
    }

    /**
     * Apply options to instance.
     */
    private static void applyOptions(RuntimeHash self, RuntimeHash opts) {
        for (Map.Entry<String, RuntimeScalar> entry : opts.elements.entrySet()) {
            String key = entry.getKey();
            RuntimeScalar value = entry.getValue();

            // Validate certain options
            if (key.equals("sep_char") || key.equals("quote_char") || key.equals("escape_char")) {
                if (value.type != RuntimeScalarType.UNDEF && value.toString().length() != 1) {
                    setError(self, INI_SEPARATOR_CONFLICT,
                            "INI - " + key + " must be exactly one character", 0, 0);
                    continue;
                }
            }

            self.put(key, value);
        }
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
