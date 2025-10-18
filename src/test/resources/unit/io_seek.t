#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Fcntl qw(SEEK_SET SEEK_CUR SEEK_END);

# Test data
my $test_content = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
my $test_counter = 0;

# Helper function to create unique test filenames
sub get_test_filename {
    return "test_seek_operator_" . (++$test_counter) . "_" . $$ . ".tmp";
}

# Helper function to cleanup test files
sub cleanup_file {
    my $filename = shift;
    unlink $filename if -e $filename;
}

# Helper to create a test file with content
sub create_test_file {
    my ($filename, $content) = @_;

    open my $fh, '>:raw', $filename or die "Cannot create $filename: $!";
    print $fh $content;
    close $fh;
}

# Helper to read current position content
sub read_at_position {
    my ($fh, $length) = @_;
    $length //= 10;

    my $buffer;
    read($fh, $buffer, $length);
    return $buffer;
}

subtest 'Basic seek with SEEK_SET (absolute positioning)' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Seek to beginning' => sub {
        ok(seek($fh, 0, SEEK_SET), 'seek to position 0 succeeds');
        is(tell($fh), 0, 'tell() confirms position 0');
        is(read_at_position($fh, 5), '01234', 'Read correct content at position 0');
    };

    subtest 'Seek to middle' => sub {
        ok(seek($fh, 10, SEEK_SET), 'seek to position 10 succeeds');
        is(tell($fh), 10, 'tell() confirms position 10');
        is(read_at_position($fh, 5), 'ABCDE', 'Read correct content at position 10');
    };

    subtest 'Seek to near end' => sub {
        ok(seek($fh, 58, SEEK_SET), 'seek to position 58 succeeds');
        is(tell($fh), 58, 'tell() confirms position 58');
        is(read_at_position($fh, 4), 'wxyz', 'Read correct content at position 58');
    };

    subtest 'Seek beyond EOF' => sub {
        ok(seek($fh, 100, SEEK_SET), 'seek beyond EOF succeeds');
        is(tell($fh), 100, 'tell() confirms position beyond EOF');

        my $buffer;
        my $bytes_read = read($fh, $buffer, 10);
        is($bytes_read, 0, 'read() returns 0 bytes beyond EOF');
        ok(eof($fh), 'eof() returns true');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with SEEK_CUR (relative to current position)' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Forward seek from beginning' => sub {
        seek($fh, 0, SEEK_SET); # Start at beginning
        ok(seek($fh, 5, SEEK_CUR), 'seek forward 5 bytes succeeds');
        is(tell($fh), 5, 'Position is now 5');
        is(read_at_position($fh, 3), '567', 'Read correct content');
    };

    subtest 'Forward seek from middle' => sub {
        seek($fh, 20, SEEK_SET); # Start at position 20
        ok(seek($fh, 10, SEEK_CUR), 'seek forward 10 bytes succeeds');
        is(tell($fh), 30, 'Position is now 30');
        is(read_at_position($fh, 3), 'UVW', 'Read correct content');
    };

    subtest 'Backward seek' => sub {
        seek($fh, 30, SEEK_SET); # Start at position 30
        ok(seek($fh, -10, SEEK_CUR), 'seek backward 10 bytes succeeds');
        is(tell($fh), 20, 'Position is now 20');
        is(read_at_position($fh, 3), 'KLM', 'Read correct content');
    };

    subtest 'Backward seek to before beginning' => sub {
        seek($fh, 5, SEEK_SET); # Start at position 5
        my $result = seek($fh, -10, SEEK_CUR);
        ok(!$result, 'seek backward past beginning fails');
        is(tell($fh), 5, 'Position unchanged after failed seek');
        is(read_at_position($fh, 3), '567', 'Read from unchanged position');
    };

    subtest 'Zero offset seek' => sub {
        seek($fh, 25, SEEK_SET); # Start at position 25
        my $pos_before = tell($fh);
        ok(seek($fh, 0, SEEK_CUR), 'seek with 0 offset succeeds');
        is(tell($fh), $pos_before, 'Position unchanged');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with SEEK_END (relative to end of file)' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);
    my $file_length = length($test_content);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Seek to exact end' => sub {
        ok(seek($fh, 0, SEEK_END), 'seek to end succeeds');
        is(tell($fh), $file_length, 'Position is at EOF');

        my $buffer;
        my $bytes_read = read($fh, $buffer, 10);
        is($bytes_read, 0, 'No bytes to read at EOF');
    };

    subtest 'Seek before end' => sub {
        ok(seek($fh, -10, SEEK_END), 'seek 10 bytes before end succeeds');
        is(tell($fh), $file_length - 10, 'Position is correct');
        is(read_at_position($fh, 10), 'qrstuvwxyz', 'Read last 10 bytes');
    };

    subtest 'Seek to last byte' => sub {
        ok(seek($fh, -1, SEEK_END), 'seek to last byte succeeds');
        is(tell($fh), $file_length - 1, 'Position at last byte');
        is(read_at_position($fh, 1), 'z', 'Read last byte');
    };

    subtest 'Seek beyond beginning from end' => sub {
        # First position at a known location
        seek($fh, 10, SEEK_SET);
        my $pos_before = tell($fh);

        my $result = seek($fh, -100, SEEK_END);
        ok(!$result, 'seek far before beginning fails');
        is(tell($fh), $pos_before, 'Position unchanged after failed seek');
    };

    subtest 'Seek past EOF from end' => sub {
        ok(seek($fh, 10, SEEK_END), 'seek past EOF from end succeeds');
        is(tell($fh), $file_length + 10, 'Position is beyond EOF');

        my $buffer;
        my $bytes_read = read($fh, $buffer, 10);
        is($bytes_read, 0, 'No bytes to read beyond EOF');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with numeric whence values' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Using numeric constants directly' => sub {
        # SEEK_SET = 0
        ok(seek($fh, 15, 0), 'seek with whence=0 (SEEK_SET) succeeds');
        is(tell($fh), 15, 'Position set absolutely to 15');
        is(read_at_position($fh, 3), 'FGH', 'Read correct content');

        # SEEK_CUR = 1
        ok(seek($fh, 5, 1), 'seek with whence=1 (SEEK_CUR) succeeds');
        is(tell($fh), 23, 'Position moved forward by 5');

        # SEEK_END = 2
        ok(seek($fh, -5, 2), 'seek with whence=2 (SEEK_END) succeeds');
        is(tell($fh), length($test_content) - 5, 'Position set from end');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with write operations' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);

    open my $fh, '+<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Seek and overwrite' => sub {
        ok(seek($fh, 10, SEEK_SET), 'seek to position 10');
        print $fh "XXX";

        seek($fh, 10, SEEK_SET);
        is(read_at_position($fh, 3), 'XXX', 'Data overwritten correctly');

        seek($fh, 0, SEEK_SET);
        is(read_at_position($fh, 15), '0123456789XXXDE', 'File partially overwritten');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek clears EOF flag' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, "Short content");

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    # Read to EOF
    my $buffer;
    read($fh, $buffer, 100);
    ok(eof($fh), 'EOF flag set after reading past end');

    # Seek should clear EOF
    seek($fh, 0, SEEK_SET);
    ok(!eof($fh), 'EOF flag cleared after seek');

    # Can read again
    my $bytes_read = read($fh, $buffer, 5);
    is($bytes_read, 5, 'Can read after seek clears EOF');
    is($buffer, 'Short', 'Read correct content');

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with different file modes' => sub {
    my $filename = get_test_filename();

    subtest 'Seek in append mode' => sub {
        create_test_file($filename, "Initial");

        open my $fh, '>>:raw', $filename or die "Cannot open $filename: $!";

        # In append mode, writes always go to end regardless of seek
        ok(seek($fh, 0, SEEK_SET), 'seek in append mode succeeds');
        print $fh "Appended";

        close $fh;

        # Verify content
        open my $read_fh, '<:raw', $filename or die "Cannot open $filename: $!";
        my $content = do { local $/; <$read_fh> };
        close $read_fh;

        is($content, "InitialAppended", 'Append mode ignores seek for writes');
    };

    cleanup_file($filename);
};

subtest 'Edge cases and error conditions' => sub {
    my $filename = get_test_filename();
    create_test_file($filename, $test_content);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Very large seek positions' => sub {
        my $large_pos = 2**31;  # 2GB
        ok(seek($fh, $large_pos, SEEK_SET), 'seek to very large position succeeds');

        my $buffer;
        my $bytes_read = read($fh, $buffer, 10);
        is($bytes_read, 0, 'No data at very large position');
    };

    subtest 'Negative absolute seek' => sub {
        my $pos_before = tell($fh);
        my $result = seek($fh, -5, SEEK_SET);
        ok(!$result, 'Negative absolute seek fails');
        is(tell($fh), $pos_before, 'Position unchanged after failed seek');
    };

    subtest 'Invalid whence values' => sub {
        my $pos_before = tell($fh);
        my $result = eval { seek($fh, 0, 99) };  # Invalid whence
        ok(!$result || $@, 'Invalid whence handled');
        # Position should be unchanged if seek failed
        is(tell($fh), $pos_before, 'Position unchanged after invalid whence') if !$@;
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Seek with binary data' => sub {
    my $filename = get_test_filename();

    # Create binary test data
    my $binary_data = join('', map { chr($_) } 0..255);
    create_test_file($filename, $binary_data);

    open my $fh, '<:raw', $filename or die "Cannot open $filename: $!";

    subtest 'Seek in binary file' => sub {
        ok(seek($fh, 128, SEEK_SET), 'seek to middle of binary data');

        my $buffer;
        read($fh, $buffer, 4);
        my @bytes = unpack("C*", $buffer);
        is_deeply(\@bytes, [128, 129, 130, 131], 'Read correct binary bytes after seek');
    };

    subtest 'Seek to null bytes' => sub {
        ok(seek($fh, 0, SEEK_SET), 'seek to position with null byte');

        my $buffer;
        read($fh, $buffer, 1);
        is(ord($buffer), 0, 'Read null byte correctly');
    };

    close $fh;
    cleanup_file($filename);
};

subtest 'Verify Fcntl constants' => sub {
    # Just to document what the constants should be
    is(SEEK_SET, 0, 'SEEK_SET is 0');
    is(SEEK_CUR, 1, 'SEEK_CUR is 1');
    is(SEEK_END, 2, 'SEEK_END is 2');
};

# Cleanup any remaining test files
for my $i (1..$test_counter) {
    my $filename = "test_seek_operator_${i}_$$.tmp";
    cleanup_file($filename);
}

done_testing();
