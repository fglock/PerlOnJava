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

done_testing();

