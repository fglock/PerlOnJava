use strict;
use warnings;
use Test::More tests => 7;

# %array[...] is an alternate access spelling for @array.  The core test
# exercises it without strict vars; Perl permits that spelling there.
no strict 'vars';

my @array = ('a' .. 'd');
is_deeply([%array[3 .. 4]], [3, 'd', 4, undef],
    'index/value array slices preserve list-valued indices');
is(join('|', %array[()]), '', 'an empty index/value array slice is valid');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, "@_" };
    is(scalar eval '%array[1]', 'b', 'scalar index/value slice returns its value');
}
like($warnings[0], qr/^%array\[1\] in scalar context better written as \$array\[1\]/,
    'scalar index/value slice warns with its scalar spelling');

my @indices = (1, 3);
$_++ for %array[@indices];
is_deeply(\@indices, [1, 3], 'foreach aliases values, not index expressions');

eval '%array[1, 2] = qw(B C)';
like($@, qr/^Can't modify index\/value array slice in list assignment/,
    'index/value array slices cannot be assigned');

sub array_only (\%) { }
eval 'array_only %array[1]';
like($@, qr/^Type of arg 1 to main::array_only must be hash \(not index\/value array slice\) at/,
    'hash prototype rejects an index/value array slice');
