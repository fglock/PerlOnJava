#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Comprehensive test for interpreter error handling and introspection
# Tests: die, warn, eval block, error variable, caller

print "# Testing die, warn, eval, error var, and caller in interpreter\n";

# Test 1: Basic die and eval
{
    my $result = eval {
        die "Test error\n";
        return "Should not reach here";
    };
    ok(!$result, 'eval block caught die');
    like($@, qr/Test error/, 'die message captured');
    print "# Test 1 complete\n";
}

# Test 2: Die without newline (adds location)
{
    eval {
        die "Error without newline";
    };
    like($@, qr/Error without newline at/, 'die without newline adds location');
    like($@, qr/line \d+/, 'location includes line number');
    print "# Test 2 complete\n";
}

# Test 3: Nested eval blocks
{
    my $outer;
    eval {
        eval {
            die "Inner error\n";
        };
        $outer = $@;
        die "Outer error\n";
    };
    like($outer, qr/Inner error/, 'inner error captured');
    like($@, qr/Outer error/, 'outer error captured');
    print "# Test 3 complete\n";
}

# Test 4: Eval returns undef on die
{
    my $result = eval {
        die "Test\n";
    };
    ok(!defined($result), 'eval returns undef when die occurs');
    print "# Test 4 complete\n";
}

# Test 5: Warn functionality
{
    my $warning;
    local $SIG{__WARN__} = sub { $warning = shift };

    warn "Test warning\n";

    like($warning, qr/Test warning/, 'warn message captured');
    print "# Test 5 complete\n";
}

# Test 6: Warn without newline adds location
{
    my $warning;
    local $SIG{__WARN__} = sub { $warning = shift };

    warn "Warning without newline";

    like($warning, qr/Warning without newline at/, 'warn adds location');
    like($warning, qr/line \d+/, 'warn location has line number');
    print "# Test 6 complete\n";
}

# Test 7: Caller in subroutine (0 levels)
{
    sub test_caller {
        my ($package, $filename, $line) = caller(0);
        return ($package, $filename, $line);
    }

    my ($pkg, $file, $line) = test_caller();

    is($pkg, 'main', 'caller(0) returns main package');
    ok(defined($file), 'caller(0) returns filename');
    ok($line > 0, 'caller(0) returns line number');

    print "# Test 7 - caller(0): package=$pkg, file=$file, line=$line\n";
}

# Test 8: Caller with nested calls
{
    sub inner_func {
        my ($package, $filename, $line, $subroutine) = caller(1);
        return ($package, $filename, $line, $subroutine);
    }

    sub outer_func {
        return inner_func();
    }

    my ($pkg, $file, $line, $sub) = outer_func();

    is($pkg, 'main', 'caller(1) returns correct package');
    ok(defined($file), 'caller(1) returns filename');
    ok($line > 0, 'caller(1) returns line number');
    print "# Test 8 - caller(1): package=$pkg, file=$file, line=$line\n";
}

# Test 9: Caller returns false when no caller
{
    my @caller = caller(10);  # Way too deep
    ok(!@caller, 'caller returns empty list when no caller');
    print "# Test 9 complete\n";
}

# Test 10: Die inside subroutine with stack trace
{
    sub level2 {
        die "Error in level2\n";
    }

    sub level1 {
        level2();
    }

    eval {
        level1();
    };

    like($@, qr/Error in level2/, 'die message from nested call');
    print "# Test 10 complete\n";
}

# Test 11: Error variable cleared on successful eval
{
    $@ = "Previous error\n";
    eval {
        1 + 1;  # Successful code
    };
    is($@, '', 'error variable cleared on successful eval');
    print "# Test 11 complete\n";
}

# Test 12: Eval can return values
{
    my $result = eval {
        my $x = 10;
        my $y = 20;
        $x + $y;
    };
    is($result, 30, 'eval returns last expression');
    is($@, '', 'no error on successful eval');
    print "# Test 12 - eval returned: $result\n";
}

# Test 13: Die with saved error
{
    eval {
        die "First error\n";
    };
    my $saved = $@;

    eval {
        die $saved;
    };

    is($@, $saved, 'die preserves error object');
    print "# Test 13 complete\n";
}

# Test 14: Bare die behavior
{
    my $result;

    eval {
        $@ = "Inner saved\n";
        die;  # Bare die
    };

    like($@, qr/Inner saved|Died/, 'bare die propagates error');
    print "# Test 14 complete\n";
}

# Test 15: Caller inside eval
{
    sub caller_in_eval {
        my ($pkg, $file, $line) = caller(0);
        return ($pkg, $file, $line);
    }

    my ($pkg, $file, $line) = eval {
        caller_in_eval();
    };

    is($pkg, 'main', 'caller works inside eval');
    ok(defined($file), 'caller returns file inside eval');
    print "# Test 15 - Caller in eval: $pkg at $file:$line\n";
}

done_testing();
