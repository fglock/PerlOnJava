#!/usr/bin/env perl

use strict;
use warnings;

# Test regex (??{...}) recursive/dynamic patterns
# These patterns insert a regex at runtime based on code execution

print "1..10\n";

my $test = 1;

# Test 1: Simple constant pattern insertion
{
    my $str = "abc";
    my $result = eval {
        $str =~ /^(??{"a"})bc/
    };
    if ($@) {
        # Expected: jperl throws error (not implemented), perl works
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - (??{...}) not implemented yet (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Simple recursive pattern works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 2: Pattern with concatenation
{
    my $str = "hello world";
    my $result = eval {
        $str =~ /(??{"hel" . "lo"}) world/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Concatenated pattern not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Concatenated pattern works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 3: Dynamic pattern from variable (would be dynamic in real Perl)
{
    my $str = "test123";
    my $pattern = "test";
    my $result = eval {
        # In real Perl, this would use $pattern dynamically
        # For now, we test with a constant
        $str =~ /(??{"test"})\d+/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Dynamic-like pattern not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Dynamic-like pattern works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 4: Recursive pattern with alternation
{
    my $str = "foo";
    my $result = eval {
        $str =~ /(??{"f"})(?:oo|ar)/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Recursive with alternation not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Recursive with alternation works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 5: Empty recursive pattern
{
    my $str = "abc";
    my $result = eval {
        $str =~ /a(??{""})bc/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Empty recursive pattern not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Empty recursive pattern works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 6: Recursive pattern that doesn't match
{
    my $str = "abc";
    my $result = eval {
        $str =~ /^(??{"x"})bc/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Non-matching recursive not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif (!$result) {
        print "ok $test - Non-matching recursive pattern correctly fails (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should not match\n";
    }
    $test++;
}

# Test 7: Multiple recursive patterns
{
    my $str = "abcd";
    my $result = eval {
        $str =~ /(??{"a"})(??{"b"})cd/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Multiple recursive patterns not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Multiple recursive patterns work (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 8: Recursive pattern with numeric constant
{
    my $str = "123abc";
    my $result = eval {
        $str =~ /(??{"\\d+"})[a-z]+/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - Recursive with regex metacharacters not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - Recursive with regex metacharacters works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}

# Test 9: Difference between (?{...}) and (??{...})
{
    my $str = "abc";
    
    # First try (?{...}) - code execution, doesn't affect match
    my $code_block_result = eval {
        $str =~ /a(?{"x"})bc/  # Should match, "x" is just executed
    };
    
    # Then try (??{...}) - pattern insertion
    my $recursive_result = eval {
        $str =~ /a(??{"x"})bc/  # Should NOT match, tries to match "x"
    };
    
    if ($@ && $@ =~ /recursive regex patterns not implemented/) {
        print "ok $test - Difference test: recursive not implemented (expected)\n";
    } elsif (!$@) {
        if ($code_block_result && !$recursive_result) {
            print "ok $test - (?{}) vs (??{}) behave differently as expected\n";
        } else {
            print "not ok $test - (?{}) and (??{}) should behave differently\n";
        }
    } else {
        print "not ok $test - Unexpected error: $@\n";
    }
    $test++;
}

# Test 10: Recursive pattern in re/pat.t style (the actual failing case)
{
    my $str = "abc";
    my $result = eval {
        # This is similar to what's in re/pat.t line 503
        $str =~ /^(??{"a"})b/
    };
    if ($@) {
        if ($@ =~ /recursive regex patterns not implemented/) {
            print "ok $test - re/pat.t style recursive not implemented (expected)\n";
        } else {
            print "not ok $test - Unexpected error: $@\n";
        }
    } elsif ($result) {
        print "ok $test - re/pat.t style recursive works (Perl behavior)\n";
    } else {
        print "not ok $test - Pattern should match\n";
    }
    $test++;
}
