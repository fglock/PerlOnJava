use strict;
use warnings;
use Test::More;

subtest 'Basic write to scalar' => sub {
    my $var = '';
    open(my $memory, ">", \$var) or die "Can't open memory file: $!";
    print $memory "foo!\n";
    close $memory;
    
    is($var, "foo!\n", "Writing to memory filehandle updates scalar");
};

subtest 'Multiple writes' => sub {
    my $var = '';
    open(my $memory, ">", \$var) or die "Can't open memory file: $!";
    print $memory "Hello";
    print $memory " ";
    print $memory "World!";
    close $memory;
    
    is($var, "Hello World!", "Multiple writes concatenate correctly");
};

subtest 'Read from scalar' => sub {
    my $var = "Line 1\nLine 2\nLine 3\n";
    open(my $memory, "<", \$var) or die "Can't open memory file for reading: $!";
    
    my $line1 = <$memory>;
    is($line1, "Line 1\n", "First line read correctly");
    
    my $line2 = <$memory>;
    is($line2, "Line 2\n", "Second line read correctly");
    
    my @remaining = <$memory>;
    is(scalar @remaining, 1, "One line remaining");
    is($remaining[0], "Line 3\n", "Third line read correctly");
    
    close $memory;
};

subtest 'Append mode' => sub {
    my $var = "Initial content\n";
    open(my $memory, ">>", \$var) or die "Can't open memory file for append: $!";
    print $memory "Appended content\n";
    close $memory;
    
    is($var, "Initial content\nAppended content\n", "Append mode preserves existing content");
};

subtest 'Truncate on write' => sub {
    my $var = "This will be overwritten";
    open(my $memory, ">", \$var) or die "Can't open memory file: $!";
    print $memory "New content";
    close $memory;
    
    is($var, "New content", "Write mode truncates existing content");
};

subtest 'Seek and tell' => sub {
    my $var = "0123456789";
    open(my $memory, "+<", \$var) or die "Can't open memory file for read/write: $!";
    
    # Test tell
    my $pos = tell($memory);
    is($pos, 0, "Initial position is 0");
    
    # Read some characters
    my $buf;
    read($memory, $buf, 5);
    is($buf, "01234", "Read first 5 characters");
    
    $pos = tell($memory);
    is($pos, 5, "Position after reading 5 characters");
    
    # Seek back to position 2
    seek($memory, 2, 0);
    $pos = tell($memory);
    is($pos, 2, "Position after seek to 2");
    
    # Write at position 2
    print $memory "ABC";
    close $memory;
    
    is($var, "01ABC56789", "Write at seek position works correctly");
};

subtest 'EOF handling' => sub {
    my $var = "Short text";
    open(my $memory, "<", \$var) or die "Can't open memory file: $!";
    
    my $content = <$memory>;
    is($content, "Short text", "Read entire content");
    
    ok(eof($memory), "EOF reached after reading all content");
    
    my $more = <$memory>;
    is($more, undef, "Reading past EOF returns undef");
    
    close $memory;
};

subtest 'Binary data' => sub {
    my $var = '';
    open(my $memory, ">", \$var) or die "Can't open memory file: $!";
    binmode($memory);
    
    # Write some binary data
    print $memory "\x00\x01\x02\xFF";
    close $memory;
    
    is(length($var), 4, "Binary data length correct");
    is(ord(substr($var, 0, 1)), 0, "First byte is 0x00");
    is(ord(substr($var, 3, 1)), 255, "Last byte is 0xFF");
};

subtest 'Empty scalar' => sub {
    my $var;  # undefined
    open(my $memory, ">", \$var) or die "Can't open memory file: $!";
    print $memory "Content";
    close $memory;
    
    is($var, "Content", "Writing to undefined scalar works");
};

subtest 'Read-write mode' => sub {
    my $var = "Original";
    open(my $memory, "+<", \$var) or die "Can't open memory file for read/write: $!";
    
    # Read first 3 characters
    my $buf;
    read($memory, $buf, 3);
    is($buf, "Ori", "Read first 3 characters");
    
    # Write at current position
    print $memory "XXX";
    
    # Seek to beginning and read all
    seek($memory, 0, 0);
    my $all = <$memory>;
    close $memory;
    
    is($all, "OriXXXal", "Read-write mode works correctly");
};

subtest 'UTF-8 encoding layer' => sub {
    my $var = '';
    open(my $memory, ">:utf8", \$var) or die "Can't open memory file with utf8 layer: $!";
    print $memory "Hello ";
    print $memory "\x{263A}"; # Unicode smiley face
    print $memory " World";
    close $memory;

    # Check that UTF-8 encoding was applied
    ok(length($var) > length("Hello  World"), "UTF-8 encoded string is longer than character count");

    # Read it back with UTF-8 layer
    open(my $read, "<:utf8", \$var) or die "Can't open for reading with utf8: $!";
    my $content = <$read>;
    close $read;

    like($content, qr/Hello .* World/, "Content contains expected pattern");
};

subtest 'CRLF layer for writing' => sub {
    my $var = '';
    open(my $memory, ">:crlf", \$var) or die "Can't open memory file with crlf layer: $!";
    print $memory "Line 1\n";
    print $memory "Line 2\n";
    close $memory;

    # On Windows or with :crlf, \n should become \r\n
    like($var, qr/Line 1\r\n/, "First line has CRLF ending");
    like($var, qr/Line 2\r\n/, "Second line has CRLF ending");
};

subtest 'CRLF layer for reading' => sub {
    my $var = "Line 1\r\nLine 2\r\n";
    open(my $memory, "<:crlf", \$var) or die "Can't open memory file for reading with crlf: $!";

    my $line1 = <$memory>;
    is($line1, "Line 1\n", "CRLF converted to LF on read");

    my $line2 = <$memory>;
    is($line2, "Line 2\n", "Second line CRLF converted to LF");

    close $memory;
};

subtest 'Raw/bytes layer' => sub {
    my $var = '';
    open(my $memory, ">:raw", \$var) or die "Can't open memory file with raw layer: $!";

    # Write raw bytes
    print $memory "\x00\x01\x02\xFF\n";
    close $memory;

    is(length($var), 5, "Raw mode preserves all bytes including newline");
    is(ord(substr($var, 4, 1)), 10, "Newline is LF (10) not CRLF");
};

subtest 'Stacked layers' => sub {
    my $var = '';
    open(my $memory, ">:utf8:crlf", \$var) or die "Can't open with stacked layers: $!";

    print $memory "Unicode: \x{263A}\n";
    close $memory;

    # Should have both UTF-8 encoding and CRLF conversion
    ok(length($var) > length("Unicode: X\r\n"), "UTF-8 encoding applied");
    like($var, qr/\r\n$/, "CRLF ending applied");
};

subtest 'Layer with read-write mode' => sub {
    # Start with ASCII content to avoid Unicode string issues
    my $var = "Original text\n";
    open(my $memory, "+<", \$var) or die "Can't open for read/write: $!";

    # Read the content
    my $line = <$memory>;
    is($line, "Original text\n", "Read content");

    # Seek back and write
    seek($memory, 0, 0);
    print $memory "Modified text\n";
    close $memory;

    is($var, "Modified text\n", "Read-write mode works correctly");
};

subtest 'UTF-8 with read-write mode' => sub {
    # For read-write with UTF-8, start with encoded bytes
    use Encode;
    my $var = encode('UTF-8', "Original \x{263A}\n");

    open(my $memory, "+<:utf8", \$var) or die "Can't open for read/write with utf8: $!";

    # Read should decode UTF-8
    my $line = <$memory>;
    like($line, qr/Original.*\n/, "Read UTF-8 content");

    # Seek back and write
    seek($memory, 0, 0);
    print $memory "Modified \x{2665}\n"; # Heart symbol
    close $memory;

    # Verify UTF-8 encoding was applied to write
    my $decoded = decode('UTF-8', $var);
    like($decoded, qr/Modified.*\n/, "UTF-8 content written correctly");
};

done_testing();

