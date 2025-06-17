use strict;
use warnings;
use Test::More;

# Test ref operator for different types
my $scalar = 42;
is(ref($scalar), "", 'Scalar ref type');

my $array_ref = [1, 2, 3];
is(ref($array_ref), "ARRAY", 'Array ref type');

my $hash_ref = { key => 'value' };
is(ref($hash_ref), "HASH", 'Hash ref type');

my $code_ref = sub { return 1; };
is(ref($code_ref), "CODE", 'Code ref type');

my $glob_ref = *STDOUT;
is(ref($glob_ref), "", 'Glob ref type');

my $regex_ref = qr/abc/;
is(ref($regex_ref), "Regexp", 'Regex ref type');

my $vstring = v97;
is(ref(\$vstring), "VSTRING", 'VSTRING ref type');

# Test stringification
is("$scalar", "42", 'Scalar stringification');
like("$array_ref", qr/^ARRAY\(0x[0-9a-f]+\)$/, 'Array stringification');
like("$hash_ref", qr/^HASH\(0x[0-9a-f]+\)$/, 'Hash stringification');
like("$code_ref", qr/^CODE\(0x[0-9a-f]+\)$/, 'Code stringification');
is("$glob_ref", "*main::STDOUT", 'Glob stringification');
is("$vstring", "a", 'VSTRING stringification');

# Test reference types
my $scalar_ref = \$scalar;
is(ref($scalar_ref), "SCALAR", 'Scalar reference type');

my $array_ref_ref = \$array_ref;
is(ref($array_ref_ref), "REF", 'Array reference reference type');

my $hash_ref_ref = \$hash_ref;
is(ref($hash_ref_ref), "REF", 'Hash reference reference type');

my $code_ref_ref = \$code_ref;
is(ref($code_ref_ref), "REF", 'Code reference reference type');

my $glob_ref_ref = \$glob_ref;
is(ref($glob_ref_ref), "GLOB", 'Glob reference reference type');

$glob_ref_ref = \*STDOUT;
is(ref($glob_ref_ref), "GLOB", 'Direct glob reference type');

my $regex_ref_ref = \$regex_ref;
is(ref($regex_ref_ref), "REF", 'Regex reference reference type');

my $vstring_ref = \$vstring;
is(ref($vstring_ref), "VSTRING", 'VSTRING reference type');

# Test stash entries
{
    my $stash_entry = *main::;
    is(ref($stash_entry), "", 'Stash entry ref type');

    my $stash_entry_ref = \*main::;
    is(ref($stash_entry_ref), "GLOB", 'Stash entry reference type');
}

{
    my $stash_entry = $Testing::{a};
    ok(!defined($stash_entry), 'Undefined stash entry');
}

{
    $Testing2::a = 123;
    my $stash_entry = $Testing2::{a};
    is(ref($stash_entry), "", 'Initialized stash entry ref type');

    my $stash_entry_ref = \$Testing2::{a};
    is(ref($stash_entry_ref), "GLOB", 'Initialized stash entry reference type');
    is("$stash_entry", "*Testing2::a", 'Stash entry stringification');
}

done_testing();
