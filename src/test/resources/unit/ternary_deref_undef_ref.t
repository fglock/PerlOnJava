use strict;
use warnings;
use Test::More tests => 4;

# This reproduces the ExifTool pattern:
#   my $dLen = $dataPt ? length($$dataPt) : -1;
# where $dataPt is sometimes a proxy-ish value that can be truthy but fails $$.
#
# In core Perl, a scalar reference is always truthy, even if it points at an undef scalar.
# So the true branch should run and $$ should yield undef, and length(undef) should be 0.
{
    my $x;             # undef
    my $dataPt = \$x;  # defined reference, but $$dataPt is undef

    is(defined($dataPt) ? 1 : 0, 1, 'dataPt (reference) is defined');
    is($dataPt ? 1 : 0, 1, 'dataPt (reference) is truthy');

    my $dLen = $dataPt ? length($$dataPt) : -1;
    is($dLen, 0, 'length($$dataPt) where $$dataPt is undef yields 0');

    is($$dataPt, undef, '$$dataPt is undef');
}
