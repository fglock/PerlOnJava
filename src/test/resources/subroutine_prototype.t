use strict;
use warnings;
use Test::More;

no warnings "void";

# Basic prototype tests from original file
sub empty_proto () { 42 }
is( empty_proto(), 42, "Empty prototype works like a constant" );
is( empty_proto,   42, "Empty prototype allows call without parens" );

sub scalar_proto ($) { $_[0] }
is( scalar_proto(123),   123,   "Scalar prototype accepts single argument" );
is( scalar_proto("abc"), "abc", "Scalar prototype works with strings" );

sub list_proto (@) { join( ",", @_ ) }
is( list_proto( 1, 2, 3 ), "1,2,3", "List prototype accepts multiple arguments" );

sub array_ref_proto (\@) { $_[0]->[0] }
my @arr = ( 1, 2, 3 );
is( array_ref_proto(@arr), 1, "Array reference prototype works" );

sub hash_ref_proto (\%) { keys %{ $_[0] } }
my %hash = ( a => 1, b => 2 );
is( hash_ref_proto(%hash), 2, "Hash reference prototype works" );

sub scalar_ref_proto (\$) { ${ $_[0] } }
my $val = 42;
is( scalar_ref_proto($val), 42, "Scalar reference prototype works" );

sub code_ref_proto (&) { $_[0]->() }
is( code_ref_proto { 99 }, 99, "Code reference prototype works" );

sub multi_proto ($$) { $_[0] + $_[1] }
is( multi_proto( 2, 3 ), 5, "Multiple argument prototype works" );

sub optional_proto (;$) { defined $_[0] ? $_[0] : "default" }
is( optional_proto(),       "default", "Optional argument prototype works without arg" );
is( optional_proto("test"), "test",    "Optional argument prototype works with arg" );

sub mixed_proto ($@) { $_[0] . ":" . join( ",", @_[ 1 .. $#_ ] ) }
is( mixed_proto( "head", 1, 2, 3 ), "head:1,2,3", "Mixed prototype works" );

sub slurpy_proto ($;@) { $_[0] . ":" . join( ",", @_[ 1 .. $#_ ] ) }
is( slurpy_proto("x"),         "x:",    "Slurpy prototype works with single arg" );
is( slurpy_proto( "x", 1, 2 ), "x:1,2", "Slurpy prototype works with multiple args" );

sub context_proto (_) { wantarray ? ( 1, 2, 3 ) : "scalar" }
my @list = context_proto(1);
is_deeply( \@list, [ 1, 2, 3 ], "Context prototype works in list context" );
is( scalar context_proto(1), "scalar", "Context prototype works in scalar context" );

sub bracketed_proto (\[@%]) { ref $_[0] }
@arr  = ( 1, 2, 3 );
%hash = ( a => 1 );
is( bracketed_proto(@arr),  "ARRAY", "Bracketed prototype accepts array reference" );
is( bracketed_proto(%hash), "HASH",  "Bracketed prototype accepts hash reference" );

sub multi_bracketed_proto (\[@%] \[@%]) { ref( $_[0] ) . "," . ref( $_[1] ) }
is( multi_bracketed_proto( @arr,  %hash ), "ARRAY,HASH", "Multiple bracketed prototypes work" );
is( multi_bracketed_proto( %hash, @arr ),  "HASH,ARRAY", "Bracketed prototypes work in any order" );

sub optional_bracketed_proto (;\[@%]) { defined $_[0] ? ref( $_[0] ) : "none" }
is( optional_bracketed_proto(),      "none",  "Optional bracketed prototype works without arg" );
is( optional_bracketed_proto(@arr),  "ARRAY", "Optional bracketed prototype works with array" );
is( optional_bracketed_proto(%hash), "HASH",  "Optional bracketed prototype works with hash" );

subtest "Plus (+) prototype behavior" => sub {
    sub plus_proto (+) { ref( $_[0] ) || "SCALAR" }

    my @plus_arr  = ( 1, 2, 3 );
    my %plus_hash = ( a => 1 );
    my $aref      = [ 1, 2, 3 ];
    my $href      = { x => 1 };

    is( plus_proto(@plus_arr),  "ARRAY",  "accepts literal array as reference" );
    is( plus_proto(%plus_hash), "HASH",   "accepts literal hash as reference" );
    is( plus_proto(42),         "SCALAR", "forces scalar context on numbers" );
    is( plus_proto("xyz"),      "SCALAR", "forces scalar context on strings" );
    is( plus_proto($aref),      "ARRAY",  "preserves array reference" );
    is( plus_proto($href),      "HASH",   "preserves hash reference" );
};

subtest "Star (*) prototype behavior" => sub {
    sub star_proto (*) { ref( $_[0] ) || 'SCALAR' }

    my @star_arr  = ( 1, 2, 3 );
    my %star_hash = ( a => 1 );
    my $scalar    = 42;
    local *HANDLE;

    is( star_proto(@star_arr),     'SCALAR', "accepts array in scalar context" );
    is( star_proto(%star_hash),    'SCALAR', "accepts hash in scalar context" );
    is( star_proto($scalar),       'SCALAR', "accepts scalar" );
    is( star_proto(*HANDLE),       'GLOB',   "accepts typeglob" );
    is( star_proto( \@star_arr ),  'ARRAY',  "accepts array reference" );
    is( star_proto( \%star_hash ), 'HASH',   "accepts hash reference" );
    is( star_proto( \$scalar ),    'SCALAR', "accepts scalar reference" );
    is( star_proto( sub { } ),     'CODE',   "accepts code reference" );
};

subtest "Empty prototype () edge cases" => sub {
    sub empty_test () { "empty" }

    is( empty_test(), "empty", "Empty prototype with parentheses" );
    is( empty_test,   "empty", "Empty prototype without parentheses" );

    # Test comma operator behavior
    my $result = eval q{ empty_test,1,2,3; 1 };
    ok( $result, "Empty prototype allows comma operator (parsed as empty_test(), 1, 2, 3)" );

    # Test with parentheses - should fail
    my $error = "";
    {
        local $SIG{__WARN__} = sub { $error = $_[0] };
        eval q{ empty_test(1,2,3) };
        like( $@, qr/Too many arguments/, "Empty prototype with parentheses rejects arguments" );
    }
};

subtest "Underscore (_) prototype comprehensive tests" => sub {
    sub underscore_test (_) { $_[0] // "undef" }

    # Test default behavior
    local $_ = "default_value";
    is( underscore_test(), "default_value", "Underscore prototype uses \$_ when no args" );

    # Test with explicit argument
    is( underscore_test("explicit"), "explicit", "Underscore prototype accepts explicit argument" );
    is( underscore_test(42),         42,         "Underscore prototype accepts numbers" );

    # Test with undef
    is( underscore_test(undef), "undef", "Underscore prototype accepts undef" );

    # Test too many arguments
    eval q{ underscore_test(1, 2) };
    like( $@, qr/Too many arguments/, "Underscore prototype rejects multiple arguments" );
};

done_testing();

