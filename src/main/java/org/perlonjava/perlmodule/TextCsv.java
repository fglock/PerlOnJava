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
            // Register core CSV methods (high-level methods now in Perl)
            csv.registerMethod("parse", null);
            csv.registerMethod("fields", null);
            csv.registerMethod("combine", null);
            csv.registerMethod("getline", null);
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
     * Parse a line from a filehandle.
     */
    public static RuntimeList getline(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fh = args.get(1);

        // Read a line from the filehandle
        RuntimeScalar line = Readline.readline(fh.getRuntimeIO());

        if (line.type == RuntimeScalarType.UNDEF) {
            return scalarUndef.getList();
        }

        // Parse the line
        RuntimeArray parseArgs = new RuntimeArray();
        RuntimeArray.push(parseArgs, args.get(0));
        RuntimeArray.push(parseArgs, line);

        RuntimeList result = parse(parseArgs, ctx);
        if (result.getFirst().getBoolean()) {
            return self.get("_fields").getList();
        }

        return scalarUndef.getList();
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

        cached.set(csvFormat);

        return csvFormat;
    }

    /**
     * Set error information using Perl calling convention.
     */
    private static void setError(RuntimeHash self, int code, String message, int pos, int field) {
        // Call Perl _set_error method
        RuntimeArray args = new RuntimeArray();
        RuntimeArray.push(args, self.createReference());
        RuntimeArray.push(args, new RuntimeScalar(code));
        RuntimeArray.push(args, new RuntimeScalar(message));
        RuntimeArray.push(args, new RuntimeScalar(pos));
        RuntimeArray.push(args, new RuntimeScalar(field));

        // Call the Perl method
        RuntimeCode.apply(
                GlobalVariable.getGlobalCodeRef("Text::CSV::_set_error"),
                args,
                RuntimeContextType.SCALAR
        );
    }

    /**
     * Clear error state using Perl calling convention.
     */
    private static void clearError(RuntimeHash self) {
        // Call Perl _clear_error method
        RuntimeArray args = new RuntimeArray();
        RuntimeArray.push(args, self.createReference());

        // Call the Perl method
        RuntimeCode.apply(
                GlobalVariable.getGlobalCodeRef("Text::CSV::_clear_error"),
                args,
                RuntimeContextType.SCALAR
        );
    }
}
