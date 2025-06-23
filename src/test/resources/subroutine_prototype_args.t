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

done_testing();

