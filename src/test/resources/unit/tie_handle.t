#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use 5.38.0;
use feature 'bareword_filehandles';  # Enable bareword filehandles for testing

# Test implementation of a tied filehandle class
package TiedHandle;

sub TIEHANDLE {
    my ($class, @args) = @_;
    my $self = {
        buffer => '',
        position => 0,
        args => \@args,
        mode => '<',
        eof => 0,
        binmode => 0,
        fileno => 99,  # Fake file descriptor
        read_count => 0,
        write_count => 0,
        readline_count => 0,
        getc_count => 0,
        print_count => 0,
        printf_count => 0,
        seek_count => 0,
        tell_count => 0,
        open_count => 0,
        close_count => 0,
        eof_count => 0,
        fileno_count => 0,
        binmode_count => 0,
    };
    return bless $self, $class;
}

sub READ {
    my ($self, undef, $length, $offset) = @_;
    $self->{read_count}++;
    $offset //= 0;
    
    # Check if we're at EOF
    if ($self->{position} >= length($self->{buffer})) {
        $_[1] = '' if $offset == 0;
        return 0;
    }
    
    # Read from buffer
    my $remaining = length($self->{buffer}) - $self->{position};
    my $to_read = $length < $remaining ? $length : $remaining;
    
    my $data = substr($self->{buffer}, $self->{position}, $to_read);
    $self->{position} += $to_read;
    
    if ($offset == 0) {
        $_[1] = $data;
    } else {
        $_[1] = '' unless defined $_[1];
        # Extend string if necessary
        if ($offset > length($_[1])) {
            $_[1] .= "\0" x ($offset - length($_[1]));
        }
        substr($_[1], $offset, length($data)) = $data;
    }
    
    return length($data);
}

sub READLINE {
    my ($self) = @_;
    $self->{readline_count}++;
    
    # Check if we're at EOF
    if ($self->{position} >= length($self->{buffer})) {
        return wantarray ? () : undef;
    }
    
    # Get the input record separator
    my $rs = $/;
    
    if (wantarray) {
        # Return all remaining lines
        my @lines;
        while ($self->{position} < length($self->{buffer})) {
            my $line = $self->_read_one_line($rs);
            push @lines, $line if defined $line;
        }
        return @lines;
    } else {
        # Return one line
        return $self->_read_one_line($rs);
    }
}

sub _read_one_line {
    my ($self, $rs) = @_;
    
    return undef if $self->{position} >= length($self->{buffer});
    
    # Handle different cases of $/
    if (!defined $rs) {
        # Slurp mode - read entire remaining buffer
        my $data = substr($self->{buffer}, $self->{position});
        $self->{position} = length($self->{buffer});
        return $data;
    } elsif ($rs eq '') {
        # Paragraph mode - not implemented for simplicity
        # Would need to handle multiple consecutive newlines
        die "Paragraph mode not implemented";
    } else {
        # Normal mode - look for the record separator
        my $sep_pos = index($self->{buffer}, $rs, $self->{position});
        my $line;
        
        if ($sep_pos >= 0) {
            # Found separator
            $line = substr($self->{buffer}, $self->{position}, 
                          $sep_pos - $self->{position} + length($rs));
            $self->{position} = $sep_pos + length($rs);
        } else {
            # No separator, read to end
            $line = substr($self->{buffer}, $self->{position});
            $self->{position} = length($self->{buffer});
        }
        
        return $line;
    }
}

sub GETC {
    my ($self) = @_;
    $self->{getc_count}++;
    
    # Check if we're at EOF
    if ($self->{position} >= length($self->{buffer})) {
        return undef;
    }
    
    my $char = substr($self->{buffer}, $self->{position}, 1);
    $self->{position}++;
    
    return $char;
}

sub WRITE {
    my ($self, $scalar, $length, $offset) = @_;
    $self->{write_count}++;
    $offset //= 0;
    
    # Extract data to write
    my $data = defined $length 
        ? substr($scalar, $offset, $length)
        : substr($scalar, $offset);
    
    # Write at current position
    if ($self->{position} == length($self->{buffer})) {
        # Append
        $self->{buffer} .= $data;
    } elsif ($self->{position} > length($self->{buffer})) {
        # Extend buffer with nulls
        $self->{buffer} .= "\0" x ($self->{position} - length($self->{buffer}));
        $self->{buffer} .= $data;
    } else {
        # Overwrite - may need to extend buffer
        my $end_pos = $self->{position} + length($data);
        if ($end_pos > length($self->{buffer})) {
            # Writing past end - extend buffer
            $self->{buffer} = substr($self->{buffer}, 0, $self->{position}) . $data;
        } else {
            # Overwrite within buffer
            substr($self->{buffer}, $self->{position}, length($data)) = $data;
        }
    }
    
    $self->{position} += length($data);
    
    return length($data);
}

sub PRINT {
    my ($self, @list) = @_;
    $self->{print_count}++;
    
    my $data = join('', @list);
    return $self->WRITE($data, length($data), 0) ? 1 : 0;
}

sub PRINTF {
    my ($self, $format, @list) = @_;
    $self->{printf_count}++;
    
    my $data = sprintf($format, @list);
    return $self->WRITE($data, length($data), 0) ? 1 : 0;
}

sub BINMODE {
    my ($self, $layer) = @_;
    $self->{binmode_count}++;
    $self->{binmode} = 1;
    $self->{binmode_layer} = $layer if defined $layer;
    return 1;
}

sub EOF {
    my ($self) = @_;
    $self->{eof_count}++;
    return $self->{position} >= length($self->{buffer});
}

sub FILENO {
    my ($self) = @_;
    $self->{fileno_count}++;
    return $self->{fileno};
}

sub SEEK {
    my ($self, $position, $whence) = @_;
    $self->{seek_count}++;
    
    my $new_position;
    if ($whence == 0) {  # SEEK_SET
        $new_position = $position;
    } elsif ($whence == 1) {  # SEEK_CUR
        $new_position = $self->{position} + $position;
    } elsif ($whence == 2) {  # SEEK_END
        $new_position = length($self->{buffer}) + $position;
    } else {
        return 0;
    }
    
    # Validate position
    if ($new_position < 0) {
        return 0;
    }
    
    $self->{position} = $new_position;
    
    # Extend buffer if seeking past end
    if ($new_position > length($self->{buffer})) {
        $self->{buffer} .= "\0" x ($new_position - length($self->{buffer}));
    }
    
    return 1;
}

sub TELL {
    my ($self) = @_;
    $self->{tell_count}++;
    return $self->{position};
}

sub OPEN {
    my ($self, $mode, @args) = @_;
    $self->{open_count}++;
    
    # Reset state
    $self->{mode} = $mode || '<';
    $self->{position} = 0;
    
    # Handle different modes
    if ($mode eq '>') {
        # Write mode - truncate
        $self->{buffer} = '';
    } elsif ($mode eq '>>') {
        # Append mode
        $self->{position} = length($self->{buffer});
    }
    # For '<' (read) mode, keep existing buffer
    
    return 1;
}

sub CLOSE {
    my ($self) = @_;
    $self->{close_count}++;
    # Could reset state here
    return 1;
}

sub DESTROY {
    my ($self) = @_;
    # Could clean up resources
}

sub UNTIE {
    my ($self) = @_;
    # Called when untie happens
}

# Test class that tracks method calls
package TrackedTiedHandle;
our @ISA = ('TiedHandle');
our @method_calls;

sub TIEHANDLE {
    my ($class, @args) = @_;
    push @method_calls, ['TIEHANDLE', @args];
    return $class->SUPER::TIEHANDLE(@args);
}

sub READ {
    my ($self, undef, $length, $offset) = @_;
    push @method_calls, ['READ', $length, $offset];
    return $self->SUPER::READ(@_);
}

sub READLINE {
    my ($self) = @_;
    push @method_calls, ['READLINE'];
    return $self->SUPER::READLINE();
}

sub GETC {
    my ($self) = @_;
    push @method_calls, ['GETC'];
    return $self->SUPER::GETC();
}

sub WRITE {
    my ($self, $scalar, $length, $offset) = @_;
    push @method_calls, ['WRITE', $scalar, $length, $offset];
    return $self->SUPER::WRITE($scalar, $length, $offset);
}

sub PRINT {
    my ($self, @list) = @_;
    push @method_calls, ['PRINT', @list];
    return $self->SUPER::PRINT(@list);
}

sub PRINTF {
    my ($self, $format, @list) = @_;
    push @method_calls, ['PRINTF', $format, @list];
    return $self->SUPER::PRINTF($format, @list);
}

sub BINMODE {
    my ($self, $layer) = @_;
    push @method_calls, ['BINMODE', $layer // ''];
    return $self->SUPER::BINMODE($layer);
}

sub EOF {
    my ($self) = @_;
    push @method_calls, ['EOF'];
    return $self->SUPER::EOF();
}

sub FILENO {
    my ($self) = @_;
    push @method_calls, ['FILENO'];
    return $self->SUPER::FILENO();
}

sub SEEK {
    my ($self, $position, $whence) = @_;
    push @method_calls, ['SEEK', $position, $whence];
    return $self->SUPER::SEEK($position, $whence);
}

sub TELL {
    my ($self) = @_;
    push @method_calls, ['TELL'];
    return $self->SUPER::TELL();
}

sub OPEN {
    my ($self, $mode, @args) = @_;
    push @method_calls, ['OPEN', $mode, @args];
    return $self->SUPER::OPEN($mode, @args);
}

sub CLOSE {
    my ($self) = @_;
    push @method_calls, ['CLOSE'];
    return $self->SUPER::CLOSE();
}

sub DESTROY {
    my ($self) = @_;
    push @method_calls, ['DESTROY'];
    return $self->SUPER::DESTROY() if $self->can('SUPER::DESTROY');
}

sub UNTIE {
    my ($self) = @_;
    push @method_calls, ['UNTIE'];
    return $self->SUPER::UNTIE() if $self->can('SUPER::UNTIE');
}

# Main test package
package main;

subtest 'Basic tie operations' => sub {
    local *FH;

    # Test tie with no arguments
    my $obj = tie *FH, 'TiedHandle';
    ok(defined $obj, 'tie returns object');
    isa_ok($obj, 'TiedHandle', 'returned object has correct class');

    # Test tied() function
    my $tied_obj = tied *FH;
    is($tied_obj, $obj, 'tied() returns the same object');

    # Test untie
    my $untie_result = untie *FH;
    ok($untie_result, 'untie returns true');

    # Verify handle is no longer tied
    is(tied *FH, undef, 'tied() returns undef after untie');
};

subtest 'Tie with arguments' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle', 'arg1', 'arg2', 42;

    is_deeply($obj->{args}, ['arg1', 'arg2', 42], 'arguments passed to TIEHANDLE');
};

subtest 'PRINT operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Single print
    print FH "Hello";
    is($obj->{buffer}, "Hello", 'print writes to buffer');
    is($obj->{print_count}, 1, 'PRINT called once');

    # Multiple arguments
    print FH " ", "World", "!";
    is($obj->{buffer}, "Hello World!", 'print with multiple arguments');
    is($obj->{print_count}, 2, 'PRINT called twice');

    # Print with newline
    print FH "\n";
    is($obj->{buffer}, "Hello World!\n", 'print with newline');
};

subtest 'PRINTF operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Basic printf
    printf FH "Number: %d", 42;
    is($obj->{buffer}, "Number: 42", 'printf formats correctly');
    is($obj->{printf_count}, 1, 'PRINTF called once');

    # Multiple format specifiers
    printf FH ", String: %s, Float: %.2f", "test", 3.14159;
    is($obj->{buffer}, "Number: 42, String: test, Float: 3.14", 
       'printf with multiple format specifiers');
};

subtest 'READLINE operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "Line 1\nLine 2\nLine 3";
    $obj->{position} = 0;

    # Read one line
    my $line = <FH>;
    is($line, "Line 1\n", 'readline returns first line');
    is($obj->{readline_count}, 1, 'READLINE called once');

    # Read another line
    $line = <FH>;
    is($line, "Line 2\n", 'readline returns second line');

    # Read all remaining lines
    my @lines = <FH>;
    is_deeply(\@lines, ["Line 3"], 'readline in list context');

    # Read from EOF
    $line = <FH>;
    is($line, undef, 'readline returns undef at EOF');
};

subtest 'GETC operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "ABC";
    $obj->{position} = 0;

    # Read characters
    is(getc(FH), 'A', 'getc returns first character');
    is(getc(FH), 'B', 'getc returns second character');
    is(getc(FH), 'C', 'getc returns third character');
    is(getc(FH), undef, 'getc returns undef at EOF');

    is($obj->{getc_count}, 4, 'GETC called four times');
};

subtest 'READ operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "Hello World!";
    $obj->{position} = 0;

    # Read into scalar
    my $data;
    my $bytes = read(FH, $data, 5);
    is($bytes, 5, 'read returns correct byte count');
    is($data, 'Hello', 'read fills scalar correctly');

    # Read with offset
    $bytes = read(FH, $data, 6, 5);
    is($bytes, 6, 'read with offset returns correct byte count');
    is($data, 'Hello World', 'read with offset appends correctly');

    # Read past EOF
    $bytes = read(FH, $data, 10);
    is($bytes, 1, 'read returns remaining bytes');
    is($data, '!', 'read at near-EOF');

    # Read at EOF
    $bytes = read(FH, $data, 10);
    is($bytes, 0, 'read returns 0 at EOF');
    is($data, '', 'read clears scalar at EOF');
};

subtest 'WRITE operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Write data
    my $data = "Test data";
    my $bytes = $obj->WRITE($data, length($data), 0);
    is($bytes, 9, 'WRITE returns correct byte count');
    is($obj->{buffer}, 'Test data', 'WRITE updates buffer');

    # Write with length limit
    $bytes = $obj->WRITE("1234567890", 5, 0);
    is($bytes, 5, 'WRITE respects length limit');
    is($obj->{buffer}, 'Test data12345', 'partial write appends');

    # Write with offset
    $bytes = $obj->WRITE("ABCDEFGHIJ", 3, 5);
    is($bytes, 3, 'WRITE with offset works');
    is($obj->{buffer}, 'Test data12345FGH', 'write with offset');
};

subtest 'SEEK and TELL operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "0123456789";
    $obj->{position} = 0;

    # TELL initial position
    is(tell(FH), 0, 'tell returns initial position');

    # SEEK_SET (absolute)
    ok(seek(FH, 5, 0), 'seek absolute succeeds');
    is(tell(FH), 5, 'position updated after seek');

    # SEEK_CUR (relative)
    ok(seek(FH, 2, 1), 'seek relative succeeds');
    is(tell(FH), 7, 'position updated after relative seek');

    ok(seek(FH, -3, 1), 'negative relative seek succeeds');
    is(tell(FH), 4, 'position updated after negative seek');

    # SEEK_END
    ok(seek(FH, -2, 2), 'seek from end succeeds');
    is(tell(FH), 8, 'position set from end');

    # Seek past end
    ok(seek(FH, 15, 0), 'seek past end succeeds');
    is(tell(FH), 15, 'position past end');
    is(length($obj->{buffer}), 15, 'buffer extended with nulls');

    # Invalid seek
    ok(!seek(FH, -20, 0), 'negative absolute seek fails');
};

subtest 'EOF operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "Test";
    $obj->{position} = 0;

    ok(!eof(FH), 'not at EOF initially');

    # Read to end
    $obj->{position} = 4;
    ok(eof(FH), 'at EOF after reading all');

    # Seek back
    seek(FH, 0, 0);
    ok(!eof(FH), 'not at EOF after seeking to start');

    is($obj->{eof_count}, 3, 'EOF called three times');
};

subtest 'FILENO operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    is(fileno(FH), 99, 'fileno returns fake descriptor');
    is($obj->{fileno_count}, 1, 'FILENO called once');
};

subtest 'BINMODE operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    ok(binmode(FH), 'binmode succeeds');
    is($obj->{binmode}, 1, 'binmode flag set');
    is($obj->{binmode_count}, 1, 'BINMODE called once');

    # With layer
    ok(binmode(FH, ':utf8'), 'binmode with layer succeeds');
    is($obj->{binmode_layer}, ':utf8', 'layer stored');
};

subtest 'OPEN operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set initial content
    $obj->{buffer} = "Initial content";

    # Open for writing (truncate)
    ok($obj->OPEN('>'), 'open for write succeeds');
    is($obj->{buffer}, '', 'buffer truncated');
    is($obj->{position}, 0, 'position reset');

    # Add content and open for append
    $obj->{buffer} = "New content";
    ok($obj->OPEN('>>'), 'open for append succeeds');
    is($obj->{buffer}, 'New content', 'buffer preserved');
    is($obj->{position}, 11, 'position at end');

    # Open for reading
    ok($obj->OPEN('<'), 'open for read succeeds');
    is($obj->{position}, 0, 'position reset for read');
};

subtest 'CLOSE operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    ok(close(FH), 'close succeeds');
    is($obj->{close_count}, 1, 'CLOSE called once');
};

subtest 'Method call tracking' => sub {
    @TrackedTiedHandle::method_calls = ();  # Clear method calls

    local *FH;
    tie *FH, 'TrackedTiedHandle', 'init_arg';

    # Verify TIEHANDLE was called
    is($TrackedTiedHandle::method_calls[0][0], 'TIEHANDLE', 'TIEHANDLE called');
    is($TrackedTiedHandle::method_calls[0][1], 'init_arg', 'TIEHANDLE received argument');

    # Clear for next tests
    @TrackedTiedHandle::method_calls = ();

    # Test various operations
    print FH "test";
    is($TrackedTiedHandle::method_calls[0][0], 'PRINT', 'PRINT called');
    is($TrackedTiedHandle::method_calls[0][1], 'test', 'PRINT data');

    @TrackedTiedHandle::method_calls = ();
    printf FH "%s", "formatted";
    is($TrackedTiedHandle::method_calls[0][0], 'PRINTF', 'PRINTF called');
    is($TrackedTiedHandle::method_calls[0][1], '%s', 'PRINTF format');
    is($TrackedTiedHandle::method_calls[0][2], 'formatted', 'PRINTF argument');
};

subtest 'Multiple tied handles' => sub {
    local (*FH1, *FH2);
    my $obj1 = tie *FH1, 'TiedHandle';
    my $obj2 = tie *FH2, 'TiedHandle';

    # Verify they are independent
    print FH1 "First handle";
    print FH2 "Second handle";

    is($obj1->{buffer}, 'First handle', 'first handle has correct content');
    is($obj2->{buffer}, 'Second handle', 'second handle has correct content');
    isnt($obj1, $obj2, 'separate objects created');
};

subtest 'Handle duplication and aliasing' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    print FH "Original content";

    # Create an alias
    local *ALIAS = *FH;
    print ALIAS " via alias";

    is($obj->{buffer}, 'Original content via alias', 'alias writes to same handle');
};

subtest 'Mixed read/write operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Write some data
    print FH "Line 1\nLine 2\n";

    # Seek to beginning
    seek(FH, 0, 0);

    # Read first line
    my $line = <FH>;
    is($line, "Line 1\n", 'read after write works');

    # Write at current position
    print FH "Modified\n";

    # Seek and read all
    seek(FH, 0, 0);
    my $all = do { local $/; <FH> };
    is($all, "Line 1\nModified\n", 'mixed operations work correctly');
};

subtest 'Select and autoflush' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Select the tied handle
    my $old = select(FH);

    # Print to selected handle
    print "To selected handle";
    is($obj->{buffer}, 'To selected handle', 'print to selected handle works');

    # Restore old handle
    select($old);
};

subtest 'Error handling' => sub {
    # Test with a broken tied handle implementation
    {
        package BrokenTiedHandle;

        sub TIEHANDLE { bless {}, shift }
        sub PRINT { die "PRINT died" }
        sub READLINE { die "READLINE died" }
        sub GETC { die "GETC died" }

        package main;

        local *FH;
        tie *FH, 'BrokenTiedHandle';

        # Test error handling
        eval { print FH "test" };
        like($@, qr/PRINT died/, 'PRINT error propagated');

        eval { my $line = <FH> };
        like($@, qr/READLINE died/, 'READLINE error propagated');

        eval { my $char = getc(FH) };
        like($@, qr/GETC died/, 'GETC error propagated');
    }
};

subtest 'Handle with references' => sub {
    local *FH;
    tie *FH, 'TiedHandle';

    # Get reference to glob
    my $fh_ref = \*FH;
    print $fh_ref "Via reference";

    my $obj = tied *FH;
    is($obj->{buffer}, 'Via reference', 'print via glob reference works');

    # Use in subroutine
    sub write_to_handle {
        my $fh = shift;
        print $fh " in sub";
    }

    write_to_handle(\*FH);
    is($obj->{buffer}, 'Via reference in sub', 'handle passed to subroutine works');
};

subtest 'Special variables and tied handles' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up data with multiple lines
    $obj->{buffer} = "Line 1\nLine 2\nLine 3\n";
    $obj->{position} = 0;

    # Test $/ (input record separator)
    {
        local $/ = "2\n";
        my $chunk = <FH>;
        is($chunk, "Line 1\nLine 2\n", 'custom $/ works');
    }

    # Reset position
    seek(FH, 0, 0);

    # Test $/ = undef (slurp mode)
    {
        local $/ = undef;
        my $all = <FH>;
        is($all, "Line 1\nLine 2\nLine 3\n", 'slurp mode works');
    }
};

subtest 'DESTROY and UNTIE' => sub {
    # Test with TrackedTiedHandle to verify DESTROY is called
    {
        @TrackedTiedHandle::method_calls = ();  # Clear method calls

        local *FH;
        tie *FH, 'TrackedTiedHandle';
        print FH "test";

        # Clear method calls before untie
        @TrackedTiedHandle::method_calls = ();

        # Untie should trigger UNTIE and DESTROY
        untie *FH;

        # Check that UNTIE was called
        my $untie_called = 0;
        my $destroy_called = 0;
        for my $call (@TrackedTiedHandle::method_calls) {
            if (ref($call) eq 'ARRAY') {
                if ($call->[0] eq 'UNTIE') {
                    $untie_called = 1;
                } elsif ($call->[0] eq 'DESTROY') {
                    $destroy_called = 1;
                }
            }
        }
        ok($untie_called, 'UNTIE called on untie');
        ok($destroy_called, 'DESTROY called on untie');
    }

    # Test with a class that doesn't implement DESTROY
    {
        package NoDestroyTiedHandle;

        sub TIEHANDLE {
            my ($class) = @_;
            return bless {}, $class;
        }

        sub PRINT { return 1 }
        sub READLINE { return undef }

        package main;

        local *FH;
        tie *FH, 'NoDestroyTiedHandle';

        # This should not throw an error even though DESTROY doesn't exist
        eval { untie *FH; };
        ok(!$@, 'untie works even when DESTROY is not implemented');
    }
};

subtest 'Edge cases with READ' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Set up test data
    $obj->{buffer} = "0123456789";
    $obj->{position} = 0;

    # Read with very large offset
    my $data = "initial data";
    my $bytes = read(FH, $data, 5, 100);
    is($bytes, 5, 'read with large offset returns bytes read');
    is(length($data), 105, 'scalar extended to accommodate offset');
    is(substr($data, 100, 5), '01234', 'data placed at correct offset');

    # Read zero bytes
    $bytes = read(FH, $data, 0);
    is($bytes, 0, 'read zero bytes returns 0');

    # Read with negative length (should be treated as 0)
    # Note: actual behavior may vary by implementation
    eval { $bytes = read(FH, $data, -5); };
    ok(!$@ || $bytes == 0, 'read with negative length handled');
};

subtest 'Binary data handling' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Write binary data
    my $binary = "\x00\x01\x02\xFF\xFE\xFD";
    print FH $binary;
    is(length($obj->{buffer}), 6, 'binary data written correctly');

    # Read binary data
    seek(FH, 0, 0);
    my $read_data;
    read(FH, $read_data, 6);
    is($read_data, $binary, 'binary data read correctly');

    # Test with null bytes in middle
    seek(FH, 0, 0);
    $obj->{buffer} = "abc\x00def\x00ghi";
    
    my $line = <FH>;
    is($line, "abc\x00def\x00ghi", 'readline handles null bytes');
};

subtest 'Large data operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Write large amount of data
    my $chunk = "x" x 1000;
    for (1..100) {
        print FH $chunk;
    }
    is(length($obj->{buffer}), 100000, 'large write successful');

    # Read in chunks
    seek(FH, 0, 0);
    my $total_read = 0;
    my $buffer;
    while (my $bytes = read(FH, $buffer, 1000)) {
        $total_read += $bytes;
    }
    is($total_read, 100000, 'large read successful');
};

subtest 'Format strings with special characters' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Printf with various format specifiers
    printf FH "%d %x %o %b", 255, 255, 255, 255;
    is($obj->{buffer}, '255 ff 377 11111111', 'multiple number formats');

    # Clear buffer
    $obj->{buffer} = '';
    $obj->{position} = 0;

    # Printf with width and precision
    printf FH "|%10s|%-10s|%10.5f|", "test", "test", 3.14159265;
    is($obj->{buffer}, '|      test|test      |   3.14159|', 'width and precision work');

    # Clear buffer
    $obj->{buffer} = '';
    $obj->{position} = 0;

    # Printf with special characters
    printf FH "%%s = %s, %%d = %d", "string", 42;
    is($obj->{buffer}, '%s = string, %d = 42', 'escaped percent signs work');
};

subtest 'Seek beyond buffer with write' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Seek beyond current buffer
    seek(FH, 10, 0);
    print FH "data";

    # Check buffer has nulls
    is(length($obj->{buffer}), 14, 'buffer extended');
    is(substr($obj->{buffer}, 0, 10), "\0" x 10, 'nulls added');
    is(substr($obj->{buffer}, 10, 4), 'data', 'data at correct position');
};

subtest 'Readline with different line endings' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Unix line endings
    $obj->{buffer} = "line1\nline2\nline3";
    $obj->{position} = 0;
    my @lines = <FH>;
    is_deeply(\@lines, ["line1\n", "line2\n", "line3"], 'Unix line endings');

    # Windows line endings
    $obj->{buffer} = "line1\r\nline2\r\nline3";
    $obj->{position} = 0;
    @lines = <FH>;
    is_deeply(\@lines, ["line1\r\n", "line2\r\n", "line3"], 'Windows line endings');

    # Mac classic line endings
    $obj->{buffer} = "line1\rline2\rline3";
    $obj->{position} = 0;
    @lines = <FH>;
    # Default readline uses \n, so this will be one line
    is(scalar @lines, 1, 'Mac line endings treated as one line by default');
};

subtest 'Interaction with $|' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Note: $| (autoflush) is typically handled by the IO system,
    # not by tied handles directly, but we test the interface
    select(FH);
    local $| = 1;  # Set autoflush

    print "autoflush test";
    is($obj->{buffer}, 'autoflush test', 'print works with autoflush set');

    select(STDOUT);  # Restore
};

subtest 'GETC with UTF-8' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Note: GETC returns bytes, not characters
    $obj->{buffer} = "ABC\x{C3}\x{A9}";  # "ABCé" in UTF-8
    $obj->{position} = 0;

    is(getc(FH), 'A', 'first ASCII character');
    is(getc(FH), 'B', 'second ASCII character');
    is(getc(FH), 'C', 'third ASCII character');
    is(getc(FH), "\x{C3}", 'first byte of UTF-8 character');
    is(getc(FH), "\x{A9}", 'second byte of UTF-8 character');
};

subtest 'Handle used in different contexts' => sub {
    local *FH;
    tie *FH, 'TiedHandle';

    # In if condition
    if (print FH "test") {
        pass('print in if condition works');
    }

    # In while condition  
    my $obj = tied *FH;
    $obj->{buffer} = "line1\nline2\n";
    $obj->{position} = 0;

    my @collected;
    while (my $line = <FH>) {
        push @collected, $line;
    }
    is_deeply(\@collected, ["line1\n", "line2\n"], 'readline in while works');

    # With defined
    $obj->{position} = 0;
    ok(defined <FH>, 'defined works with readline');

    # In boolean context
    ok(tied *FH, 'tied handle is true in boolean context');
};

subtest 'Write returns and error conditions' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Test print return value
    my $result = print FH "test";
    is($result, 1, 'print returns true on success');

    # Test printf return value
    $result = printf FH "%s", "test";
    is($result, 1, 'printf returns true on success');

    # Create handle that fails writes
    {
        package FailingWriteHandle;
        our @ISA = ('TiedHandle');

        sub WRITE {
            return 0;  # Indicate failure
        }

        sub PRINT {
            my ($self, @data) = @_;
            return 0;  # Indicate failure
        }

        package main;

        local *FAIL;
        tie *FAIL, 'FailingWriteHandle';

        $result = print FAIL "test";
        is($result, 0, 'print returns false on failure');
    }
};

subtest 'Mixing tied operations' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Write some data
    print FH "Initial data\n";
    print FH "Second line\n";

    # Read one line
    seek(FH, 0, 0);
    my $line = <FH>;
    is($line, "Initial data\n", 'first line read');

    # Get current position
    my $pos = tell(FH);
    is($pos, 13, 'position after first line');

    # Write at current position (overwrite)
    print FH "Overwritten\n";

    # Read all from beginning
    seek(FH, 0, 0);
    my $all = do { local $/; <FH> };
    is($all, "Initial data\nOverwritten\n", 'mixed operations result');

    # Append at end
    seek(FH, 0, 2);
    print FH "Appended\n";

    # Verify final content
    seek(FH, 0, 0);
    $all = do { local $/; <FH> };
    is($all, "Initial data\nOverwritten\nAppended\n", 'final content correct');
};

subtest 'Tied handle with layers and encodings' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Test binmode with different layers
    ok(binmode(FH, ':raw'), 'binmode :raw works');
    is($obj->{binmode_layer}, ':raw', 'raw layer stored');

    ok(binmode(FH, ':encoding(UTF-8)'), 'binmode :encoding(UTF-8) works');
    is($obj->{binmode_layer}, ':encoding(UTF-8)', 'UTF-8 encoding stored');

    ok(binmode(FH, ':crlf'), 'binmode :crlf works');
    is($obj->{binmode_layer}, ':crlf', 'crlf layer stored');

    # Multiple layers
    ok(binmode(FH, ':raw:encoding(UTF-8)'), 'multiple layers work');
    is($obj->{binmode_layer}, ':raw:encoding(UTF-8)', 'multiple layers stored');
};

subtest 'Complex seek patterns' => sub {
    local *FH;
    my $obj = tie *FH, 'TiedHandle';

    # Create buffer with known content
    $obj->{buffer} = "0123456789ABCDEFGHIJ";

    # Series of seeks and reads
    seek(FH, 5, 0);
    my $char = getc(FH);
    is($char, '5', 'read after seek to 5');
    is(tell(FH), 6, 'position updated after getc');

    # Seek backward from current
    seek(FH, -3, 1);
    is(tell(FH), 3, 'backward seek from current');

    # Seek forward from current
    seek(FH, 10, 1);
    is(tell(FH), 13, 'forward seek from current');

    # Seek from end
    seek(FH, -5, 2);
    is(tell(FH), 15, 'seek from end');

    # Read at position
    $char = getc(FH);
    is($char, 'F', 'correct character at position 15');
};

done_testing();

