package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.runtimetypes.*;

/** Java XS bridge for the pure-Perl JSON::DWIW compatibility backend. */
public class JSONDWIW extends PerlModuleBase {
    public JSONDWIW() { super("JSON::DWIW", false); }

    public static void initialize() {
        JSONDWIW module = new JSONDWIW();
        try {
            for (String method : new String[] {
                    "do_dummy_parse", "has_deserialize", "deserialize", "deserialize_file",
                    "_xs_to_json", "have_big_int", "have_big_float", "size_of_uv", "peek_scalar",
                    "has_high_bit_bytes", "is_valid_utf8", "upgrade_to_utf8", "flagged_as_utf8",
                    "flag_as_utf8", "unflag_as_utf8", "code_point_to_utf8_str",
                    "code_point_to_hex_bytes", "bytes_to_code_points", "_has_mmap",
                    "_parse_mmap_file", "_check_scalar", "skip_deserialize_file",
                    "get_ref_addr", "get_ref_type"
            }) module.registerMethod(method, null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeList call(String name, RuntimeArray args, int ctx) {
        ModuleOperators.require(new RuntimeScalar("PerlOnJava/JSONDWIWBackend.pm"));
        return RuntimeCode.apply(
                GlobalVariable.getGlobalCodeRef("PerlOnJava::JSONDWIWBackend::" + name), args, ctx);
    }

    public static RuntimeList do_dummy_parse(RuntimeArray a, int c) { return call("do_dummy_parse", a, c); }
    public static RuntimeList has_deserialize(RuntimeArray a, int c) { return call("has_deserialize", a, c); }
    public static RuntimeList deserialize(RuntimeArray a, int c) { return call("deserialize", a, c); }
    public static RuntimeList deserialize_file(RuntimeArray a, int c) { return call("deserialize_file", a, c); }
    public static RuntimeList _xs_to_json(RuntimeArray a, int c) { return call("_xs_to_json", a, c); }
    public static RuntimeList have_big_int(RuntimeArray a, int c) { return call("have_big_int", a, c); }
    public static RuntimeList have_big_float(RuntimeArray a, int c) { return call("have_big_float", a, c); }
    public static RuntimeList size_of_uv(RuntimeArray a, int c) { return call("size_of_uv", a, c); }
    public static RuntimeList peek_scalar(RuntimeArray a, int c) { return call("peek_scalar", a, c); }
    public static RuntimeList has_high_bit_bytes(RuntimeArray a, int c) { return call("has_high_bit_bytes", a, c); }
    public static RuntimeList is_valid_utf8(RuntimeArray a, int c) { return call("is_valid_utf8", a, c); }
    public static RuntimeList upgrade_to_utf8(RuntimeArray a, int c) { return call("upgrade_to_utf8", a, c); }
    public static RuntimeList flagged_as_utf8(RuntimeArray a, int c) { return call("flagged_as_utf8", a, c); }
    public static RuntimeList flag_as_utf8(RuntimeArray a, int c) { return call("flag_as_utf8", a, c); }
    public static RuntimeList unflag_as_utf8(RuntimeArray a, int c) { return call("unflag_as_utf8", a, c); }
    public static RuntimeList code_point_to_utf8_str(RuntimeArray a, int c) { return call("code_point_to_utf8_str", a, c); }
    public static RuntimeList code_point_to_hex_bytes(RuntimeArray a, int c) { return call("code_point_to_hex_bytes", a, c); }
    public static RuntimeList bytes_to_code_points(RuntimeArray a, int c) { return call("bytes_to_code_points", a, c); }
    public static RuntimeList _has_mmap(RuntimeArray a, int c) { return call("_has_mmap", a, c); }
    public static RuntimeList _parse_mmap_file(RuntimeArray a, int c) { return call("_parse_mmap_file", a, c); }
    public static RuntimeList _check_scalar(RuntimeArray a, int c) { return call("_check_scalar", a, c); }
    public static RuntimeList skip_deserialize_file(RuntimeArray a, int c) { return call("skip_deserialize_file", a, c); }
    public static RuntimeList get_ref_addr(RuntimeArray a, int c) { return call("get_ref_addr", a, c); }
    public static RuntimeList get_ref_type(RuntimeArray a, int c) { return call("get_ref_type", a, c); }
}
