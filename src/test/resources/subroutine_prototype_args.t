use strict;
use warnings;
use Test::More;

no warnings "void";

subtest "Too many arguments with strict prototypes" => sub {
    sub single_scalar ($) { $_[0] }
    sub double_scalar ($$) { "$_[0]:$_[1]" }
    sub triple_scalar ($$$) { "$_[0]:$_[1]:$_[2]" }
    
    # Too many arguments should fail - use string eval to defer compilation
    eval 'single_scalar(1, 2)';
    like( $@, qr/Too many arguments/, "Single scalar prototype rejects extra arguments" );
    
    eval 'single_scalar(1, 2, 3)';
    like( $@, qr/Too many arguments/, "Single scalar prototype rejects multiple extra arguments" );
    
    eval 'double_scalar(1, 2, 3)';
    like( $@, qr/Too many arguments/, "Double scalar prototype rejects extra arguments" );
    
    eval 'triple_scalar(1, 2, 3, 4, 5)';
    like( $@, qr/Too many arguments/, "Triple scalar prototype rejects extra arguments" );

    # Test with references
    sub ref_proto (\$) { ${$_[0]} }
    my $x = 10;
    eval 'my $x = 10; ref_proto($x, $x, $x)';
    like( $@, qr/Too many arguments/, "Reference prototype rejects extra arguments" );

    # Test with code blocks
    sub block_proto (&) { $_[0]->() }
    eval 'block_proto { 1 } { 2 }';
    like( $@, qr/syntax error/, "Block prototype doesn't parse multiple blocks" );
};

subtest "Slurpy prototypes accept extra arguments" => sub {
    sub list_test (@) { scalar @_ }
    sub mixed_test ($@) { "$_[0]:" . scalar(@_) }
    sub optional_list (;@) { scalar @_ }
    
    # These should all work fine
    is( list_test(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 10, "List prototype accepts many arguments" );
    is( mixed_test("head", 1, 2, 3, 4, 5), "head:6", "Mixed prototype accepts extra arguments" );
    is( optional_list(1, 2, 3, 4, 5), 5, "Optional list prototype accepts many arguments" );
};

subtest "Trailing commas and fat arrows" => sub {
    sub test_sub ($) { $_[0] }
    sub list_sub (@) { join(':', @_) }
    
    # Trailing commas
    is( test_sub(42,), 42, "Single scalar with trailing comma works" );
    is( list_sub(1, 2, 3,), "1:2:3", "List with trailing comma works" );
    
    # Test multiple trailing commas - this should be a syntax error
    eval 'list_sub(1, 2, 3, ,)';
    like( $@, qr/^$/, "List with multiple trailing commas works" );
    
    # Fat arrows (=>) are just fancy commas - use quoted strings for strict compliance
    is( list_sub('a' => 1, 'b' => 2), "a:1:b:2", "Fat arrows work as commas" );
    is( list_sub('a' => 1, 'b' => 2,), "a:1:b:2", "Fat arrows with trailing comma" );
    is( list_sub('a' => 1 => 'b' => 2), "a:1:b:2", "Multiple fat arrows in sequence" );
    
    # Mixed commas and fat arrows
    is( list_sub(1, 'a' => 2, 3 => 'b', 4), "1:a:2:3:b:4", "Mixed commas and fat arrows" );
    
    # Empty elements with multiple commas - these should be syntax errors
    eval 'list_sub(1, , 2, , , 3)';
    like( $@, qr/^$/, "Multiple commas work" );
    
    eval 'list_sub(1 => , => 2)';
    like( $@, qr/^$/, "Fat arrows with empty elements work" );
};

subtest "Syntax errors with too many commas in strict prototypes" => sub {
    sub strict_single ($) { $_[0] }
    sub strict_double ($$) { "$_[0]:$_[1]" }
    
    # These should still respect prototype restrictions - use string eval
    eval 'strict_single(1, 2,)';
    like( $@, qr/Too many arguments/, "Trailing comma doesn't bypass prototype check" );
    
    eval 'strict_single(1 => 2)';
    like( $@, qr/Too many arguments/, "Fat arrow counts as two arguments" );
    
    eval 'strict_double(1, 2, 3,)';
    like( $@, qr/Too many arguments/, "Trailing comma with too many args still fails" );
    
    # Test with barewords under strict - should fail
    eval 'strict_double(a => b => c)';
    like( $@, qr/(Too many arguments|Bareword)/, "Three arguments via fat arrows fail (may be bareword error first)" );
};

subtest "Edge cases with empty lists and undef" => sub {
    sub list_count (@) { scalar @_ }
    sub list_join (@) { join('|', map { defined $_ ? $_ : 'UNDEF' } @_) }
    
    # Empty elements - these should be syntax errors
    eval 'list_count(1, , 2)';
    like( $@, qr/^$/, "Empty element between commas works" );
    
    eval 'list_count(, , ,)';
    like( $@, qr/syntax error/, "Multiple empty commas produce syntax error" );
    
    eval 'list_count(1, , , , 2)';
    like( $@, qr/^$/, "Multiple empty elements work" );
    
    # Explicit undef
    is( list_count(1, undef, 2), 3, "Explicit undef counts as argument" );
    is( list_join(1, undef, 2), "1|UNDEF|2", "Explicit undef is preserved" );
    
    # Mixed - empty commas should cause syntax error
    eval 'list_count(1, , undef, , 2)';
    like( $@, qr/^$/, "Mixed empty and undef work" );
    
    eval 'list_join(1, , undef, , 2)';
    like( $@, qr/^$/, "Empty elements work with undef" );
};

subtest "Complex prototype edge cases" => sub {
    sub complex_proto ($;$@) { 
        my $first = shift;
        my $second = shift // 'DEFAULT';
        my $rest = join(',', @_);
        return "$first:$second:$rest";
    }
    
    # Normal cases
    is( complex_proto(1), "1:DEFAULT:", "Required only" );
    is( complex_proto(1, 2), "1:2:", "Required and optional" );
    is( complex_proto(1, 2, 3, 4, 5), "1:2:3,4,5", "All parameters" );
    
    # With trailing commas
    is( complex_proto(1,), "1:DEFAULT:", "Trailing comma after required" );
    is( complex_proto(1, 2,), "1:2:", "Trailing comma after optional" );
    is( complex_proto(1, 2, 3, 4,), "1:2:3,4", "Trailing comma in list" );
    
    # With fat arrows - use quoted strings
    is( complex_proto('a' => 'b' => 'c' => 'd'), "a:b:c,d", "All fat arrows" );
    is( complex_proto(1, 'a' => 'b', 'c' => 'd' => 'e'), "1:a:b,c,d,e", "Mixed syntax" );
};

subtest 'Glob and reference prototype *\$$;$' => sub {
    sub glob_ref_proto (*\$$;$) {
        my ($glob, $scalarref, $scalar, $optional) = @_;
        $optional //= 'DEFAULT';
        return "glob:" . (ref($glob) || 'GLOB') .
            ":ref:" . (ref($scalarref) || 'NOT_REF') .
            ":value:" . $$scalarref .
            ":scalar:$scalar:optional:$optional";
    }

    # Test with correct number of arguments - user passes plain scalars
    my $x = 42;
    is( glob_ref_proto(*STDOUT, $x, "hello"),
        "glob:GLOB:ref:SCALAR:value:42:scalar:hello:optional:DEFAULT",
        "Glob ref proto with minimum required arguments" );

    my $y = 100;
    is( glob_ref_proto(*STDERR, $y, "world", "extra"),
        "glob:GLOB:ref:SCALAR:value:100:scalar:world:optional:extra",
        "Glob ref proto with all arguments including optional" );

    # Test the specific "not single ref constructor" error when user tries to pass \$x
    eval 'my $x = 42; glob_ref_proto(*STDOUT, \$x, "hello")';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Explicit reference in \$ position gives specific error" );

    # Test with too few arguments - these should fail
    eval 'glob_ref_proto()';
    like( $@, qr/Not enough arguments/, "No arguments fails" );

    eval 'glob_ref_proto(*STDOUT)';
    like( $@, qr/Not enough arguments/, "Only glob argument fails" );

    eval 'my $y = 10; glob_ref_proto(*STDOUT, $y)';
    like( $@, qr/Not enough arguments/, "Missing required scalar argument fails" );

    # Test with too many arguments - should fail
    eval 'my $z = 20; glob_ref_proto(*STDOUT, $z, "one", "two", "three")';
    like( $@, qr/Too many arguments/, "Too many arguments fails" );

    eval 'my $w = 30; glob_ref_proto(*STDOUT, $w, "one", "two", "three", "four")';
    like( $@, qr/Too many arguments/, "Many extra arguments fail" );

    # Test with wrong types
    eval 'my $v = 40; glob_ref_proto("not a glob", $v, "test")';
    ok( $@ eq "", "String instead of glob is not an error" );

    # Test with array ref instead of scalar - should give the ref constructor error
    eval 'my @arr = (1,2,3); glob_ref_proto(*STDIN, \@arr, "test")';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Array ref in \$ position gives ref constructor error" );

    # Test with hash ref instead of scalar - should give the ref constructor error
    eval 'my %hash = (a => 1); glob_ref_proto(*STDIN, \%hash, "test")';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Hash ref in \$ position gives ref constructor error" );

    # Test that the reference is actually created
    my $test_var = 50;
    eval q{
        my $test_var = 50;
        sub test_ref (*\$$;$) {
            my ($glob, $ref, $scalar) = @_;
            $$ref = 99;  # Modify through the reference
        }
        test_ref(*STDOUT, $test_var, "test");
        die "Reference not working" unless $test_var == 99;
    };
    is( $@, '', "Reference prototype actually creates a reference that can modify the original" );

    # Test calling without parentheses
    my $p = 75;
    is( eval 'my $p = 75; glob_ref_proto *STDOUT, $p, "no-parens"; glob_ref_proto *STDOUT, $p, "no-parens"',
        "glob:GLOB:ref:SCALAR:value:75:scalar:no-parens:optional:DEFAULT",
        "Calling without parentheses with correct arguments works" );

    my $q = 80;
    is( eval 'my $q = 80; glob_ref_proto *STDERR, $q, "no-parens", "optional"; glob_ref_proto *STDERR, $q, "no-parens", "optional"',
        "glob:GLOB:ref:SCALAR:value:80:scalar:no-parens:optional:optional",
        "Calling without parentheses with all arguments works" );

    # Test with too few arguments without parentheses
    eval 'glob_ref_proto';
    like( $@, qr/Not enough arguments/, "No arguments without parentheses fails" );

    eval 'glob_ref_proto *STDOUT';
    like( $@, qr/Not enough arguments/, "Only glob argument without parentheses fails" );

    eval 'my $r = 10; glob_ref_proto *STDOUT, $r';
    like( $@, qr/Not enough arguments/, "Missing required scalar argument without parentheses fails" );

    # Test with too many arguments without parentheses
    eval 'my $s = 20; glob_ref_proto *STDOUT, $s, "one", "two", "three"';
    like( $@, qr/Too many arguments/, "Too many arguments without parentheses fails" );

    eval 'my $t = 30; glob_ref_proto *STDOUT, $t, "one", "two", "three", "four"';
    like( $@, qr/Too many arguments/, "Many extra arguments without parentheses fail" );

    # Test the ref constructor error without parentheses
    eval 'my $u = 42; glob_ref_proto *STDOUT, \$u, "hello"';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Explicit reference in \$ position without parentheses gives specific error" );

    # Test with array/hash refs without parentheses
    eval 'my @arr = (1,2,3); glob_ref_proto *STDIN, \@arr, "test"';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Array ref in \$ position without parentheses gives ref constructor error" );

    eval 'my %hash = (a => 1); glob_ref_proto *STDIN, \%hash, "test"';
    like( $@, qr/Type of arg 2 to main::glob_ref_proto must be scalar \(not single ref constructor\)/,
        "Hash ref in \$ position without parentheses gives ref constructor error" );
};

done_testing();

