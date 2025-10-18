#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use MIME::QuotedPrint;

# Simple implementation of lives_ok
sub lives_ok (&;$) {
    my ($code, $test_name) = @_;
    $test_name ||= 'code lives';
    
    eval { $code->() };
    ok(!$@, $test_name);
}

# Test basic encoding and decoding
subtest 'Basic encoding and decoding' => sub {
    plan tests => 8;
    
    # Simple ASCII text - encode_qp adds soft line break by default
    my $text = 'Hello, World!';
    my $encoded = encode_qp($text);
    is($encoded, "Hello, World!=\n", 'Basic ASCII text with soft line break');
    is(decode_qp($encoded), $text, 'Basic decoding works');
    
    # Text with non-printable characters
    my $text_with_tab = "Hello\tWorld";
    my $encoded_tab = encode_qp($text_with_tab);
    is($encoded_tab, "Hello\tWorld=\n", 'Tab character in middle not encoded, soft break added');
    is(decode_qp($encoded_tab), $text_with_tab, 'Tab character decoded');
    
    # Empty string
    is(encode_qp(''), '', 'Empty string encodes to empty');
    is(decode_qp(''), '', 'Empty string decodes to empty');
    
    # Text with equals sign
    my $text_equals = 'a=b';
    my $encoded_equals = encode_qp($text_equals);
    is($encoded_equals, "a=3Db=\n", 'Equals sign encoded with soft break');
    is(decode_qp($encoded_equals), $text_equals, 'Equals sign decoded');
};

# Test line ending parameter
subtest 'Line ending parameter' => sub {
    plan tests => 5;
    
    my $text = 'Hello, World!';
    
    # Default - adds soft line break with newline
    my $default = encode_qp($text);
    is($default, "Hello, World!=\n", 'Default adds soft line break');
    
    # CRLF line ending
    my $crlf = encode_qp($text, "\015\012");
    is($crlf, "Hello, World!=\015\012", 'CRLF line ending in soft break');
    
    # Unix line ending (explicit)
    my $unix = encode_qp($text, "\n");
    is($unix, "Hello, World!=\n", 'Unix line ending in soft break');
    
    # Custom line ending
    my $custom = encode_qp($text, " <EOL>");
    is($custom, "Hello, World!= <EOL>", 'Custom line ending in soft break');
    
    # Empty line ending (special case - enables binary mode, no soft break)
    my $no_eol = encode_qp("Hello\nWorld", "");
    is($no_eol, "Hello=0AWorld", 'Empty EOL enables binary mode, no soft break');
};

# Test 76 character line limit and soft breaks
subtest 'Line breaking at 76 characters' => sub {
    plan tests => 5;
    
    # String that's exactly 75 characters (76 with the = for soft break)
    my $text75 = 'A' x 75;
    my $encoded75 = encode_qp($text75);
    is($encoded75, $text75 . "=\n", 'Line of 75 chars gets soft break at end');
    
    # String that's exactly 76 characters needs to break
    my $text76 = 'A' x 76;
    my $encoded76 = encode_qp($text76);
    like($encoded76, qr/^.{75}=\n.+=\n$/, '76 char line broken at 75 chars');
    
    # String longer than 76 characters gets soft breaks
    my $text_long = 'A' x 100;
    my $encoded_long = encode_qp($text_long);
    # Count soft breaks (=\n sequences)
    my $soft_break_count = () = $encoded_long =~ /=\n/g;
    ok($soft_break_count >= 2, 'Long line has multiple soft breaks');
    
    # Decode should remove soft breaks
    is(decode_qp($encoded_long), $text_long, 'Soft breaks removed on decode');
    
    # Ensure soft breaks don't break encoded sequences
    my $text_break = ('X' x 74) . "\xFF";  # High byte will encode to =FF
    my $encoded_break = encode_qp($text_break);
    unlike($encoded_break, qr/=F=\n/, 'Encoded sequence not split by soft break');
};

# Test binary mode
subtest 'Binary mode' => sub {
    plan tests => 6;
    
    my $text_with_newline = "Hello\nWorld";
    
    # Normal mode - newline preserved as-is, soft break at end
    my $normal = encode_qp($text_with_newline);
    is($normal, "Hello\nWorld=\n", 'Normal mode preserves literal newlines, adds soft break');
    
    # Binary mode - newline encoded, soft break at end
    my $binary = encode_qp($text_with_newline, "\n", 1);
    is($binary, "Hello=0AWorld=\n", 'Binary mode encodes newlines, adds soft break');
    
    # Binary mode with CRLF
    my $binary_crlf = encode_qp($text_with_newline, "\015\012", 1);
    is($binary_crlf, "Hello=0AWorld=\015\012", 'Binary mode with CRLF soft break');
    
    # Empty EOL implies binary mode - no soft break
    my $empty_eol = encode_qp($text_with_newline, "");
    is($empty_eol, "Hello=0AWorld", 'Empty EOL implies binary mode, no soft break');
    
    # Binary data
    my $binary_data = "\x00\x01\x02\x03\x04\x05";
    my $encoded_binary = encode_qp($binary_data, "\n", 1);
    is($encoded_binary, "=00=01=02=03=04=05=\n", 'Binary data encoded with soft break');
    is(decode_qp($encoded_binary), $binary_data, 'Binary data decoded');
};

# Test character encoding rules
subtest 'Character encoding rules' => sub {
    plan tests => 8;
    
    # Printable ASCII characters - long line will be broken
    my $printable = '!"#$%&\'()*+,-./0123456789:;<>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~';
    my $encoded_printable = encode_qp($printable);
    like($encoded_printable, qr/=\n.*=\n$/, 'Long printable ASCII gets soft breaks');
    is(decode_qp($encoded_printable), $printable, 'Printable ASCII decodes correctly');
    
    # Space at end of line should be encoded
    my $trailing_space = "Hello ";
    my $encoded_space = encode_qp($trailing_space);
    is($encoded_space, "Hello=20=\n", 'Trailing space encoded before soft break');
    
    # Tab at end of line should be encoded  
    my $trailing_tab = "Hello\t";
    my $encoded_tab = encode_qp($trailing_tab);
    is($encoded_tab, "Hello=09=\n", 'Trailing tab encoded before soft break');
    
    # Control characters
    my $control = "\x00\x01\x02\x03\x04\x05\x06\x07\x08\x0B\x0C\x0E\x0F";
    my $encoded_control = encode_qp($control);
    is($encoded_control, "=00=01=02=03=04=05=06=07=08=0B=0C=0E=0F=\n", 'Control characters encoded');
    
    # High bytes (128-255)
    my $high_bytes = "\x80\x90\xA0\xB0\xC0\xD0\xE0\xF0\xFF";
    my $encoded_high = encode_qp($high_bytes);
    is($encoded_high, "=80=90=A0=B0=C0=D0=E0=F0=FF=\n", 'High bytes encoded');
    
    # Space and tab in middle of line not encoded
    my $space_tab = "Hello world\there";
    my $encoded_st = encode_qp($space_tab);
    is($encoded_st, "Hello world\there=\n", 'Space and tab in middle not encoded');
    
    # Equals sign must be encoded
    my $equals = "2+2=4";
    my $encoded_eq = encode_qp($equals);
    is($encoded_eq, "2+2=3D4=\n", 'Equals sign encoded as =3D');
};

# Test decode special cases
subtest 'Decode special cases' => sub {
    plan tests => 8;
    
    # Soft line breaks
    my $soft_break = "Hello=\nWorld";
    is(decode_qp($soft_break), "HelloWorld", 'Soft line break removed');
    
    # CRLF preserved in encoded text, converted to LF on decode
    my $crlf_encoded = "Hello\r\nWorld";
    is(decode_qp($crlf_encoded), "Hello\nWorld", 'CRLF converted to LF on decode');
    
    # Invalid escape sequences (should be left as-is)
    is(decode_qp("=XY"), "=XY", 'Invalid hex sequence left as-is');
    is(decode_qp("=2"), "=2", 'Incomplete hex sequence left as-is');
    is(decode_qp("="), "=", 'Lone equals at end left as-is');
    
    # Mixed case hex
    is(decode_qp("=3D=3d"), "==", 'Mixed case hex works');
    
    # Lowercase hex
    is(decode_qp("=0a=0b=0c"), "\x0A\x0B\x0C", 'Lowercase hex works');
    
    # Trailing whitespace after soft break should be ignored
    my $soft_with_space = "Hello= \nWorld";
    is(decode_qp($soft_with_space), "HelloWorld", 'Space after soft break ignored');
};

# Test edge cases  
subtest 'Edge cases' => sub {
    plan tests => 6;
    
    # Very long line without spaces
    my $long_no_space = 'A' x 200;
    my $encoded_long = encode_qp($long_no_space);
    my $decoded_long = decode_qp($encoded_long);
    is($decoded_long, $long_no_space, 'Very long line round-trip');
    
    # All possible byte values
    my $all_bytes = join('', map { chr($_) } 0..255);
    my $all_encoded = encode_qp($all_bytes, "\n", 1);
    my $all_decoded = decode_qp($all_encoded);
    is($all_decoded, $all_bytes, 'All byte values round-trip');
    
    # Line ending exactly at 75 characters (position 76 is the = for soft break)
    my $exact_75 = ('X' x 75);
    my $encoded_exact = encode_qp($exact_75);
    is($encoded_exact, $exact_75 . "=\n", '75 character line gets soft break');
    
    # Equals at end of line - encoder breaks line before the equals to avoid splitting =3D
    my $text_end_eq = ('X' x 74) . '=';
    my $encoded_end_eq = encode_qp($text_end_eq);
    # The encoder will put 74 X's, then a soft break, then =3D on the next line
    is($encoded_end_eq, ('X' x 74) . "=\n=3D=\n", 'Equals at position 75 handled correctly');
    
    # Multiple consecutive equals  
    is(decode_qp("=3D=3D=3D"), "===", 'Multiple encoded equals decoded correctly');
    
    # Empty lines preserved
    my $empty_lines = "Hello\n\nWorld";
    my $encoded_empty = encode_qp($empty_lines);
    is(decode_qp($encoded_empty), $empty_lines, 'Empty lines preserved');
};

# Test undef handling
subtest 'Undef and special cases' => sub {
    plan tests => 4;
    
    # encode_qp with undef
    {
        local $SIG{__WARN__} = sub {};
        my $encoded_undef = encode_qp(undef);
        is($encoded_undef, '', 'encode_qp(undef) returns empty string');
    }
    
    # decode_qp with undef
    {
        local $SIG{__WARN__} = sub {};
        my $decoded_undef = decode_qp(undef);
        is($decoded_undef, '', 'decode_qp(undef) returns empty string');
    }
    
    # encode_qp with reference (should stringify)
    my $ref = [];
    my $encoded_ref = encode_qp($ref);
    ok($encoded_ref, 'encode_qp with reference works (stringifies)');
    
    # decode_qp with reference (should stringify)
    my $decoded_ref = decode_qp($ref);
    ok(defined $decoded_ref, 'decode_qp with reference works (stringifies)');
};

# Test real-world examples
subtest 'Real-world examples' => sub {
    plan tests => 4;
    
    # Email header example - note high bytes will be encoded
    my $subject = "Re: Test message";
    my $encoded_subject = encode_qp($subject);
    is($encoded_subject, "Re: Test message=\n", 'Simple subject with soft break');
    
    # URL with special characters
    my $url = "http://example.com/path?param=value&other=test";
    my $encoded_url = encode_qp($url);
    is($encoded_url, "http://example.com/path?param=3Dvalue&other=3Dtest=\n", 'URL with = encoded');
    
    # Text with mixed content
    my $mixed = "Normal text\tWith tabs\nAnd lines\x00And nulls";
    my $encoded_mixed = encode_qp($mixed, "\n", 1);
    my $decoded_mixed = decode_qp($encoded_mixed);
    is($decoded_mixed, $mixed, 'Mixed content round-trip');
    
    # Soft break in middle of word
    my $long_word = 'A' x 80;
    my $encoded_word = encode_qp($long_word);
    my $decoded_word = decode_qp($encoded_word);
    is($decoded_word, $long_word, 'Long word with soft break round-trip');
};

done_testing();

