use strict;
use warnings;
use Test::More tests => 5;

{
    package ReferenceStringificationS;
    use overload '""' => sub { 'Object S' };
    sub new { bless [] }

    ::ok(do { \my $v } =~ /^SCALAR/, 'scalar reference stringification');
    ::ok(do { \\my $v } =~ /^REF/, 'reference-to-reference stringification');
    ::ok([] =~ /^ARRAY/, 'array reference stringification');
    ::ok({} =~ /^HASH/, 'hash reference stringification');
    ::ok('ReferenceStringificationS'->new =~ /^Object S/,
        'object stringification');
}
