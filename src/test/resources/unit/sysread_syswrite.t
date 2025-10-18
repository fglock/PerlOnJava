#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

# Plan tests
plan tests => 8;

subtest 'Basic syswrite and sysread' => sub {
    plan tests => 4;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    close $fh;
    
    # Test syswrite
    open(my $out, '>', $filename) or die "Cannot open $filename: $!";
    my $data = "Hello, World!\n";
    my $bytes_written = syswrite($out, $data);
    is($bytes_written, length($data), 'syswrite returns correct byte count');
    close($out);
    
    # Test sysread
    open(my $in, '<', $filename) or die "Cannot open $filename: $!";
    my $buffer;
    my $bytes_read = sysread($in, $buffer, 1024);
    is($bytes_read, length($data), 'sysread returns correct byte count');
    is($buffer, $data, 'sysread reads correct data');
    close($in);
    
    # Test with offset
    open($in, '<', $filename) or die "Cannot open $filename: $!";
    $buffer = "PREFIX:";
    $bytes_read = sysread($in, $buffer, 5, length($buffer));
    is($buffer, "PREFIX:Hello", 'sysread with offset works correctly');
    close($in);
};

subtest 'UTF-8 layer error handling' => sub {
    plan tests => 2;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    print $fh "Test data\n";
    close $fh;
    
    # Test with UTF-8 layer (should fail)
    open(my $utf8, '<:utf8', $filename) or die "Cannot open $filename: $!";
    my $buffer;
    eval {
        sysread($utf8, $buffer, 1024);
    };
    ok($@, 'sysread with :utf8 layer throws error');
    like($@, qr/sysread/, 'Error message mentions sysread');
    close($utf8);
};

subtest 'In-memory file handles (expected to fail)' => sub {
    plan tests => 2;
    
    # Note: sysread/syswrite don't work with in-memory file handles in standard Perl
    # This is a known limitation
    my $mem_content = '';
    open(my $mem_out, '>', \$mem_content) or die "Cannot open in-memory handle: $!";
    
    my $data = "In-memory test\n";
    my $bytes = syswrite($mem_out, $data);
    ok(!defined($bytes), 'syswrite to in-memory handle returns undef (expected limitation)');
    close($mem_out);
    
    # Test sysread from in-memory variable
    $mem_content = "Test content";
    open(my $mem_in, '<', \$mem_content) or die "Cannot open in-memory handle: $!";
    my $buffer;
    my $read = sysread($mem_in, $buffer, 1024);
    ok(!defined($read), 'sysread from in-memory handle returns undef (expected limitation)');
    close($mem_in);
};

subtest 'Edge cases and boundary conditions' => sub {
    plan tests => 8;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    close $fh;
    
    # Empty file
    open(my $empty, '>', $filename) or die "Cannot open $filename: $!";
    close($empty);
    
    open(my $in, '<', $filename) or die "Cannot open $filename: $!";
    my $buffer = "UNCHANGED";
    my $read = sysread($in, $buffer, 100);
    is($read, 0, 'sysread from empty file returns 0');
    is($buffer, '', 'Buffer becomes empty string when reading 0 bytes');
    close($in);
    
    # Zero-byte read request
    open(my $out, '>', $filename) or die "Cannot open $filename: $!";
    syswrite($out, "Some data");
    close($out);
    
    open($in, '<', $filename) or die "Cannot open $filename: $!";
    $buffer = "UNCHANGED";
    $read = sysread($in, $buffer, 0);
    is($read, 0, 'Zero-byte sysread returns 0');
    is($buffer, '', 'Buffer becomes empty with zero-byte read');
    close($in);
    
    # Reading past EOF
    open($in, '<', $filename) or die "Cannot open $filename: $!";
    $buffer = '';
    $read = sysread($in, $buffer, 100);  # File has only 9 bytes
    is($read, 9, 'sysread past EOF returns actual bytes available');
    is($buffer, "Some data", 'sysread past EOF reads all available data');
    
    # Second read should return 0 (EOF)
    my $read2 = sysread($in, $buffer, 100);
    is($read2, 0, 'sysread at EOF returns 0');
    close($in);
    
    # Large offset beyond buffer size
    open($in, '<', $filename) or die "Cannot open $filename: $!";
    $buffer = "ABC";
    $read = sysread($in, $buffer, 4, 10);
    is(substr($buffer, 10, 4), "Some", 'sysread with large offset extends buffer');
    close($in);
};

subtest 'Binary data handling' => sub {
    plan tests => 4;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    close $fh;
    
    # Write binary data
    open(my $out, '>:raw', $filename) or die "Cannot open $filename: $!";
    my $binary_data = pack("C*", 0, 1, 2, 255, 254, 253);
    my $written = syswrite($out, $binary_data);
    is($written, length($binary_data), 'syswrite binary data returns correct count');
    close($out);
    
    # Read binary data
    open(my $in, '<:raw', $filename) or die "Cannot open $filename: $!";
    my $buffer;
    my $read = sysread($in, $buffer, 1024);
    is($read, length($binary_data), 'sysread binary data returns correct count');
    is($buffer, $binary_data, 'Binary data read correctly');
    
    # Verify individual bytes
    my @bytes = unpack("C*", $buffer);
    is_deeply(\@bytes, [0, 1, 2, 255, 254, 253], 'Binary bytes preserved correctly');
    close($in);
};

subtest 'Large data transfers' => sub {
    plan tests => 3;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    close $fh;
    
    # Create large data (1MB)
    my $large_data = "x" x (1024 * 1024);
    
    open(my $out, '>', $filename) or die "Cannot open $filename: $!";
    my $written = syswrite($out, $large_data);
    is($written, length($large_data), 'syswrite 1MB data returns correct count');
    close($out);
    
    # Read in chunks
    open(my $in, '<', $filename) or die "Cannot open $filename: $!";
    my $total_read = 0;
    my $chunk_size = 8192;
    my $buffer;
    
    while (my $read = sysread($in, $buffer, $chunk_size, $total_read)) {
        $total_read += $read;
    }
    
    is($total_read, length($large_data), 'Total bytes read matches data size');
    is(substr($buffer, 0, $total_read), $large_data, 'Large data read correctly in chunks');
    close($in);
};

subtest 'File position handling with sysread/syswrite' => sub {
    plan tests => 6;
    
    my ($fh, $filename) = tempfile(UNLINK => 1);
    print $fh "0123456789ABCDEFGHIJ";
    close $fh;
    
    # Note: tell() may not work correctly with sysread/syswrite
    # Testing actual read behavior instead
    open(my $in, '<', $filename) or die "Cannot open $filename: $!";
    
    # Read first 5 bytes
    my $buffer;
    sysread($in, $buffer, 5);
    is($buffer, "01234", 'First read correct');
    
    # Next read should continue from position 5
    sysread($in, $buffer, 5);
    is($buffer, "56789", 'Sequential read continues correctly');
    
    # Seek and read
    seek($in, 10, 0);
    sysread($in, $buffer, 5);
    is($buffer, "ABCDE", 'Read after seek correct');
    
    # Next read continues from new position
    sysread($in, $buffer, 5);
    is($buffer, "FGHIJ", 'Read continues after seek');
    
    # Seek to beginning
    seek($in, 0, 0);
    sysread($in, $buffer, 3);
    is($buffer, "012", 'Read after seek to beginning');
    
    # Test mixed operations with proper expectations
    close($in);
    open(my $rw, '+<', $filename) or die "Cannot open $filename: $!";
    sysread($rw, $buffer, 5);
    seek($rw, 5, 0);  # Explicitly seek to position 5
    syswrite($rw, "XXXXX");
    seek($rw, 0, 0);
    sysread($rw, $buffer, 20);
    is($buffer, "01234XXXXXABCDEFGHIJ", 'Mixed read/write operations work correctly');
    close($rw);
};

subtest 'Error conditions' => sub {
    plan tests => 4;
    
    # Closed filehandle
    my ($fh, $filename) = tempfile(UNLINK => 1);
    close $fh;
    
    my $buffer;
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned = 1 if $_[0] =~ /closed filehandle/ };
    
    sysread($fh, $buffer, 100);
    ok($warned, 'sysread on closed filehandle produces warning');
    
    $warned = 0;
    syswrite($fh, "data");
    ok($warned, 'syswrite on closed filehandle produces warning');
    
    # Read-only file for writing
    open(my $ro, '<', $filename) or die "Cannot open $filename: $!";
    $warned = 0;
    local $SIG{__WARN__} = sub { $warned = 1 if $_[0] =~ /opened only for input/ };
    syswrite($ro, "data");
    ok($warned, 'syswrite on read-only handle produces warning');
    close($ro);
    
    # Write-only file for reading
    open(my $wo, '>', $filename) or die "Cannot open $filename: $!";
    $warned = 0;
    local $SIG{__WARN__} = sub { $warned = 1 if $_[0] =~ /opened only for output/ };
    sysread($wo, $buffer, 100);
    ok($warned, 'sysread on write-only handle produces warning');
    close($wo);
};

done_testing();

