#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test data
my $utf8_text = "Hello 世界 café naïve résumé";
my $test_counter = 0;

# Helper function to create unique test filenames
sub get_test_filename {
    return "test_io_layer_" . (++$test_counter) . "_" . $$ . ".tmp";
}

# Helper function to cleanup test files
sub cleanup_file {
    my $filename = shift;
    unlink $filename if -e $filename;
}

# Helper to dump bytes in hex
sub dump_bytes {
    my ($data, $label) = @_;
    $label //= "Data";
    my @bytes = unpack("C*", $data);
    my $hex = join(" ", map { sprintf("%02X", $_) } @bytes);
    diag("$label: " . length($data) . " bytes: $hex");

    # Also show ASCII representation
    my $ascii = join("", map { ($_ >= 32 && $_ <= 126) ? chr($_) : '.' } @bytes);
    diag("$label ASCII: $ascii");

    # Check for UTF-8 multibyte sequences
    my $has_multibyte = grep { $_ >= 0x80 } @bytes;
    diag("$label has multibyte: " . ($has_multibyte ? "YES" : "NO"));
}

subtest 'UTF-8 debugging tests' => sub {
    my $filename = get_test_filename();

    subtest 'Debug UTF-8 output' => sub {
        diag("Original text: '$utf8_text'");
        diag("Original text length: " . length($utf8_text));

        # Show what UTF-8 encoding should produce
        my $expected_utf8 = $utf8_text;
        utf8::encode($expected_utf8);
        dump_bytes($expected_utf8, "Expected UTF-8");

        # Open file with :utf8 layer
        open my $out, '>:utf8', $filename or die "Cannot open $filename: $!";

        # Write the text
        print $out $utf8_text;
        close $out;

        # Check file size
        my $file_size = -s $filename;
        diag("File size: $file_size bytes");

        # Read as raw bytes
        open my $raw, '<:raw', $filename or die "Cannot open $filename: $!";
        my $raw_content = do { local $/; <$raw> };
        close $raw;

        dump_bytes($raw_content, "Actual file content");

        # Compare expected vs actual
        is(length($raw_content), length($expected_utf8), 'File size matches expected UTF-8 size');

        # Check byte by byte
        my @expected_bytes = unpack("C*", $expected_utf8);
        my @actual_bytes = unpack("C*", $raw_content);

        my $max_bytes = @expected_bytes > @actual_bytes ? @expected_bytes : @actual_bytes;
        for (my $i = 0; $i < $max_bytes; $i++) {
            my $exp = $expected_bytes[$i] // 'undef';
            my $act = $actual_bytes[$i] // 'undef';
            if ($exp ne $act) {
                diag("Byte $i differs: expected " . (defined $exp ? sprintf("0x%02X", $exp) : 'undef') .
                    ", got " . (defined $act ? sprintf("0x%02X", $act) : 'undef'));
            }
        }
    };

    subtest 'Debug UTF-8 input' => sub {
        # Read with :utf8 layer
        open my $in, '<:utf8', $filename or die "Cannot open $filename: $!";
        my $read_text = do { local $/; <$in> };
        close $in;

        diag("Read text: '$read_text'");
        diag("Read text length: " . length($read_text));

        # Character by character comparison
        my @orig_chars = split //, $utf8_text;
        my @read_chars = split //, $read_text;

        for (my $i = 0; $i < @orig_chars || $i < @read_chars; $i++) {
            my $orig = $orig_chars[$i] // '';
            my $read = $read_chars[$i] // '';
            if ($orig ne $read) {
                diag("Char $i differs: expected '" . $orig . "' (U+" . sprintf("%04X", ord($orig)) .
                    "), got '" . $read . "' (U+" . sprintf("%04X", ord($read)) . ")");
            }
        }
    };

    cleanup_file($filename);
};

subtest 'Layer behavior tests' => sub {
    my $filename = get_test_filename();

    subtest 'Test simple ASCII through UTF-8 layer' => sub {
        my $ascii_text = "Hello World 123";

        open my $out, '>:utf8', $filename or die "Cannot open $filename: $!";
        print $out $ascii_text;
        close $out;

        open my $raw, '<:raw', $filename or die "Cannot open $filename: $!";
        my $raw_content = do { local $/; <$raw> };
        close $raw;

        dump_bytes($raw_content, "ASCII through UTF-8 layer");

        is($raw_content, $ascii_text, 'ASCII text unchanged by UTF-8 layer');
    };

    subtest 'Test single UTF-8 character' => sub {
        my $single_char = "世";  # Single Chinese character

        open my $out, '>:utf8', $filename or die "Cannot open $filename: $!";
        print $out $single_char;
        close $out;

        open my $raw, '<:raw', $filename or die "Cannot open $filename: $!";
        my $raw_content = do { local $/; <$raw> };
        close $raw;

        dump_bytes($raw_content, "Single UTF-8 character '世'");

        # This character should encode to 3 bytes in UTF-8
        is(length($raw_content), 3, 'Single Chinese character encodes to 3 bytes');

        # Read it back
        open my $in, '<:utf8', $filename or die "Cannot open $filename: $!";
        my $read_char = do { local $/; <$in> };
        close $in;

        is($read_char, $single_char, 'Single UTF-8 character roundtrip');
    };

    cleanup_file($filename);
};

subtest 'Raw write and UTF-8 read test' => sub {
    my $filename = get_test_filename();

    # Manually write UTF-8 bytes
    my @utf8_bytes = (
        0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20,  # "Hello "
        0xE4, 0xB8, 0x96,                    # "世"
        0xE7, 0x95, 0x8C,                    # "界"
    );

    open my $raw_out, '>:raw', $filename or die "Cannot open $filename: $!";
    print $raw_out pack("C*", @utf8_bytes);
    close $raw_out;

    diag("Wrote raw UTF-8 bytes: " . join(" ", map { sprintf("%02X", $_) } @utf8_bytes));

    # Read with :utf8 layer
    open my $utf8_in, '<:utf8', $filename or die "Cannot open $filename: $!";
    my $text = do { local $/; <$utf8_in> };
    close $utf8_in;

    diag("Read text: '$text'");
    is($text, "Hello 世界", 'Raw UTF-8 bytes read correctly with :utf8 layer');

    cleanup_file($filename);
};

# Cleanup any remaining test files
for my $i (1..$test_counter) {
    my $filename = "test_io_layer_${i}_$.tmp";
    cleanup_file($filename);
}

done_testing();
