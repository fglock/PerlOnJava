use strict;
use warnings;
use Test::More tests => 4;

{
    package FallbackUndefBoolOnly;
    use overload bool => sub { ${ $_[0] } };
}

{
    package FallbackUndefNumifyOnly;
    use overload '0+' => sub { ${ $_[0] } };
}

{
    package FallbackTrueNumifyOnly;
    use overload '0+' => sub { ${ $_[0] } }, fallback => 1;
}

my $bool_only = bless(\do { my $v = 42 }, 'FallbackUndefBoolOnly');
my $num_only = bless(\do { my $v = 42 }, 'FallbackUndefNumifyOnly');
my $fallback_true = bless(\do { my $v = 42 }, 'FallbackTrueNumifyOnly');

ok(!eval { ($bool_only + 1) == 43 }, 'bool-only overload without fallback does not fall through to native plus');
like($@, qr/no method found/, 'bool-only plus reports missing overload method');

ok(!eval { ($num_only + 1) == 43 }, 'numify-only overload without fallback does not autogenerate binary plus');
is($fallback_true + 1, 43, 'fallback true allows binary plus via numification');
