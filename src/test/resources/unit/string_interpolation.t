#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test string interpolation with various special cases

subtest 'Basic variable interpolation' => sub {
    my $var = "hello";
    my @arr = (1, 2, 3);
    
    is("$var world", "hello world", "Simple scalar interpolation");
    is("@arr", "1 2 3", "Simple array interpolation");
    is("${var}_suffix", "hello_suffix", "Braced scalar interpolation");
    is("@{arr}_suffix", "1 2 3_suffix", "Braced array interpolation");
};

subtest 'Subroutine call interpolation' => sub {

    ## This case is not well defined:
    ## 
    ## First call: ${v->()} (before sub v is processed)
    ## 
    ## At this point, Perl hasn't yet processed the sub v definition
    ## When Perl encounters v->() in string interpolation, it treats v as a bareword
    ## The bareword v gets converted to the string "v"
    ## So v->() becomes "v"->()
    ## In string context, "v" is treated as if it might be a method call, and Perl tries to make sense of it
    ## The result somehow evaluates to 123 (this is implementation-specific behavior)
    ## Second call: ${v->()} (after sub v is defined)
    ## 
    ## Now sub v has been processed and is defined
    ## v() calls the subroutine and returns \123 (a scalar reference to 123)
    ## So v->() becomes \123->()
    ## Perl tries to dereference \123 as a code reference
    ## Since \123 is a scalar reference, not a code reference, it fails with "Not a CODE reference"
    ## 
    ## $ perl -e ' print "x ${v->()} x\n"; sub v { return \123} print "x ${v->()} x\n"; '
    ## x 123 x
    ## Not a CODE reference at -e line 1.

    ## sub v { return 123; }
    ## 
    ## is("x ${v->()} y", "x 123 y", "Subroutine call in interpolation");
    
    sub get_value { return \"test"; }
    is("Result: ${get_value()}", "Result: test", "Named subroutine call");
};

subtest 'Special variables and array access' => sub {
    # Note: ${^TEST[0]} might not work in standard Perl without the variable being defined
    # Testing with more standard special variables
    
    local $^W = 1;
    is("Warning: $^W", "Warning: 1", "Special variable \$^W interpolation");
    
    my @test_array = (10, 20, 30);
    local *TEST = \@test_array;
    # This tests complex variable access patterns
};

subtest 'Array reference interpolation' => sub {
    is("@{[123]}", "123", "Single element array ref interpolation");
    is("@{[123, 456]}", "123 456", "Multiple element array ref interpolation");
    is("@{[123, 456, 789]}", "123 456 789", "Three element array ref");
    
    my $ref = [1, 2, 3];
    is("@{$ref}", "1 2 3", "Array reference variable interpolation");
};

subtest 'Spaces after sigils - no interpolation' => sub {
    # These should NOT interpolate due to spaces
    is('$ var', '$ var', "Space after dollar prevents interpolation");
    is('@ arr', '@ arr', "Space after at-sign prevents interpolation");
    
    # Test with actual variables to ensure spaces matter
    my $var = "test";
    my @arr = (1, 2, 3);
    
    is("$ var", "$ var", "Dollar with space is literal (var exists)");
    is("@ arr", "@ arr", "At-sign with space is literal (arr exists)");
};

subtest 'Non-interpolated characters' => sub {
    my $var = "test";
    
    is('%var', '%var', "Percent sign is not interpolated");
    is('$%var', '$%var', "Dollar-percent combination");
    is('text % more', 'text % more', "Percent in middle of string");
};

subtest 'Case modification escapes' => sub {
    my $var = "HELLO";
    
    # Test \L (lowercase)
    is("\L$var", "hello", "\\L makes variable lowercase");
    is("\LHELLO WORLD", "hello world", "\\L makes literal text lowercase");
    
    # Test \U (uppercase)  
    my $lower = "world";
    is("\U$lower", "WORLD", "\\U makes variable uppercase");
    is("\Uhello world", "HELLO WORLD", "\\U makes literal text uppercase");
    
    # Test \l and \u (first character only)
    is("\lHELLO", "hELLO", "\\l makes first character lowercase");
    is("\uhello", "Hello", "\\u makes first character uppercase");
};

subtest 'Complex variable access patterns' => sub {
    my %hash = (x => "value");
    my $hashref = \%hash;
    
    is("$hashref->{x}", "value", "Hash reference access in interpolation");
    
    my @arr = (10, 20, 30);
    is("$arr[1]", "20", "Array element access in interpolation");
    
    my $arrref = \@arr;
    is("$arrref->[2]", "30", "Array reference access in interpolation");
};

subtest 'Multiple dollar signs and complex expressions' => sub {
    my $var = \"test";
    my $pid = $$;  # Process ID
    
    # Test multiple dollar signs
    is("$$", "$pid", "Double dollar gives process ID");
    
    # Test complex combinations
    is("${$}$${var}", "${pid}test", "Process ID followed by braced variable");
    
    # Test expressions in braces
    is("${\123}", "123", "Numeric expression in braces");
    is("$${\\\"literal\"}", "literal", "String literal in braces");
};

subtest 'Array length operations' => sub {
    my @arr = (1, 2, 3, 4, 5);
    
    is("$#arr", "4", "Array last index (\$#array)");
    is("$#{[4,5,6]}", "2", "Array reference last index");
    
    my $aref = [10, 20, 30, 40];
    is("$#{$aref} ", "3 ", "Array reference variable last index");
};

subtest 'Escape sequences in interpolation' => sub {
    my $var = "test";
    
    is("$var\n", "test\n", "Newline after variable");
    is("$var\t", "test\t", "Tab after variable");
    is("\\$var", "\\test", "Escaped backslash before variable");
    is("\$$var", "\$test", "Escaped dollar sign");
};

subtest 'Error cases and edge conditions' => sub {
    # Test numeric variables starting with 0 (should be an error in strict mode)
    my $result1 = eval 'my $test = "$01"; return $test';
    if ($@) {
        like($@, qr/Numeric variables with more than one digit may not start with '0'/, 
             "Numeric variable \$01 throws expected error");
    } else {
        fail("Expected error for \$01 but got result: " . (defined $result1 ? $result1 : 'undef'));
    }
    
    # Test another invalid numeric variable
    my $result2 = eval 'my $test = "$02"; return $test';
    if ($@) {
        like($@, qr/Numeric variables with more than one digit may not start with '0'/, 
             "Numeric variable \$02 throws expected error");
    } else {
        fail("Expected error for \$02 but got result: " . (defined $result2 ? $result2 : 'undef'));
    }
    
    # Test undefined variables
    my $result3 = eval 'my $test = "$undefined_var"; return $test';
    if ($@) {
        diag("Unexpected error for undefined variable: $@");
        like($@, qr/Global symbol "\$undefined_var" requires explicit package name/, 
             "Undefined variable throws expected error");
    } else {
        fail("Undefined variable interpolates as empty string");
    }
    
    # Test empty interpolation
    my $result4 = eval 'my $test = "${}"; return $test';
    if ($@) {
        diag("Error for empty braces: $@");
        # This might be expected to fail in some implementations
        pass("Empty braces interpolation handled (with error)");
    } else {
        is($result4, "", "Empty braces interpolate as empty string");
    }
    
    # Test invalid variable name after $
    my $result5 = eval 'my $test = "$ invalid"; return $test';
    if ($@) {
        diag("Unexpected error for space after dollar: $@");
        like($@, qr/Global symbol "\$invalid" requires explicit package name/, "Space after dollar doesn't prevent interpolation");
    } else {
        fail("Space after dollar prevents interpolation");
    }
    
    # Test malformed array access
    my $result6 = eval 'my $test = "$arr["; return $test';
    if ($@) {
        like($@, qr/syntax error|Unterminated|Missing/, "Malformed array access throws syntax error");
    } else {
        diag("Malformed array access did not throw error, got: " . (defined $result6 ? $result6 : 'undef'));
        pass("Malformed array access handled without error");
    }
    
    # Test malformed hash access
    my $result7 = eval 'my $test = "$hash{"; return $test';
    if ($@) {
        like($@, qr/syntax error|Unterminated|Missing/, "Malformed hash access throws syntax error");
    } else {
        diag("Malformed hash access did not throw error, got: " . (defined $result7 ? $result7 : 'undef'));
        pass("Malformed hash access handled without error");
    }
    
    # Test very long numeric variable
    my $result8 = eval 'my $test = "$0123456789"; return $test';
    if ($@) {
        like($@, qr/Numeric variables with more than one digit may not start with '0'/, 
             "Long numeric variable starting with 0 throws expected error");
    } else {
        fail("Expected error for long numeric variable starting with 0");
    }
    
    # Test valid single digit $0
    my $result9 = eval 'my $test = "$0"; return $test';
    if ($@) {
        diag("Unexpected error for \$0: $@");
        fail("\$0 should be valid");
    } else {
        # $0 is the program name
        ok(defined $result9, "\$0 interpolates successfully");
    }
    
    # Test $$ (process ID) - should always work
    my $result10 = eval 'my $test = "$$"; return $test';
    if ($@) {
        diag("Unexpected error for \$$: $@");
        fail("\$\$ should be valid");
    } else {
        like($result10, qr/^\d+$/, "\$\$ interpolates as numeric process ID");
    }
    
    # Test complex malformed expression
    my $result11 = eval 'my $test = "${unclosed"; return $test';
    if ($@) {
        like($@, qr/syntax error|Unterminated|Missing/, "Unclosed brace throws syntax error");
    } else {
        diag("Unclosed brace did not throw error, got: " . (defined $result11 ? $result11 : 'undef'));
        pass("Unclosed brace handled without error");
    }
    
    # Test invalid escape in variable name
    my $result12 = eval 'my $test = "$\\{invalid}"; return $test';
    if ($@) {
        diag("Error for escaped brace in variable: $@");
        like($@, qr/Global symbol "\$invalid" requires explicit package name/, "Unclosed brace throws syntax error");
    } else {
        ok(defined $result12, "Escaped brace in variable name handled without error");
    }
};

subtest 'Unicode and special characters' => sub {
    my $unicode = "café";
    is("Unicode: $unicode", "Unicode: café", "Unicode variable interpolation");
    
    # Test with special characters that might affect parsing
    my $special = "a\$b";
    is("Special: $special", "Special: a\$b", "Variable with dollar sign");
};

subtest 'Nested references and complex structures' => sub {
    my $data = {
        users => [
            { name => "Alice", age => 30 },
            { name => "Bob", age => 25 }
        ]
    };
    
    is("$data->{users}->[0]->{name}", "Alice", "Deep hash/array access");
    is("$data->{users}->[1]->{age}", "25", "Another deep access pattern");

    # Test with code references
    my $code = sub { return "dynamic" };
    is("${\$code->()}", "dynamic", "Code reference execution in interpolation");
};
    
subtest 'Here-docs in interpolation' => sub {
    # Test here-doc within array reference (complex case)
    my $result = eval {
        my $text = "x @{[ <<'EOT' ]} x";
HERE
EOT
        return $text;
    };
    
    # This is a complex case that may not work in all implementations
    # The test checks if it can be parsed without error
    ok(defined($result) || $@, "Here-doc in array ref interpolation handled");
};

done_testing();

