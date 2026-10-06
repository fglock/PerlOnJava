use strict;
use warnings;
use Test::More tests => 2;

my $each = \&CORE::each;
my %values = (key => 'value');
my @pair = $each->(\%values);
is_deeply(\@pair, ['key', 'value'],
    'CORE::each code reference returns a hash iterator pair');

eval { $each->(1) };
like($@, qr/^Type of arg 1 to &CORE::each must be hash or array reference/,
    'CORE::each code reference rejects a non-reference argument');
