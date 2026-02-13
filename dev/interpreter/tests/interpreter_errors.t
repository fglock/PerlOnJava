use strict;
use warnings;
use Test::More;

# Test interpreter error reporting

# Test 1: Die shows correct location
{
    my $error;
    my $code = '
        sub foo {
            die "Error in foo";
        }
        foo();
    ';
    eval $code;
    $error = $@;

    # Check that error message includes location
    like($error, qr/Error in foo/, 'die message preserved');
    like($error, qr/at \(eval \d+\) line/, 'die shows eval location');

    print "# Error message: $error";
}

# Test 2: Stack trace with nested calls
{
    my $error;
    my $code = '
        sub bar {
            die "Error in bar";
        }
        sub foo {
            bar();
        }
        foo();
    ';
    eval $code;
    $error = $@;

    # Check that stack trace includes both functions
    like($error, qr/Error in bar/, 'nested die message preserved');
    like($error, qr/at \(eval \d+\)/, 'nested die shows location');

    print "# Nested error message: $error";
}

# Test 3: Multiple levels of nesting
{
    my $error;
    my $code = '
        sub level3 {
            die "Deep error";
        }
        sub level2 {
            level3();
        }
        sub level1 {
            level2();
        }
        level1();
    ';
    eval $code;
    $error = $@;

    like($error, qr/Deep error/, 'multi-level die message preserved');
    like($error, qr/at \(eval \d+\)/, 'multi-level die shows location');

    print "# Multi-level error: $error";
}

# Test 4: Die without explicit message
{
    my $error;
    my $code = '
        $@ = "Previous error\n";
        sub test_bare_die {
            die;
        }
        test_bare_die();
    ';
    eval $code;
    $error = $@;

    # Bare die should propagate $@
    like($error, qr/Previous error|Died at/, 'bare die behavior');

    print "# Bare die error: $error";
}

# Test 5: Verify line numbers are accurate
{
    my $error;
    my $code = '# Line 1
sub test_line_numbers {  # Line 2
    die "Line number test";  # Line 3
}  # Line 4
test_line_numbers();  # Line 5
';
    eval $code;
    $error = $@;

    # Should report line 3 (where die is)
    like($error, qr/at \(eval \d+\) line 3/, 'die reports correct line number');

    print "# Line number error: $error";
}

done_testing();
