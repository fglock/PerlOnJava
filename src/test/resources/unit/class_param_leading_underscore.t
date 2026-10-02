use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

if ($] < 5.044) {
    plan skip_all => 'leading underscore :param names require Perl 5.44';
}

class LeadingUnderscoreParam {
    field $_alpha :param;
    field $__beta :param;
    field $gamma_ :param;

    method values { return ($_alpha, $__beta, $gamma_) }
}

my $object = LeadingUnderscoreParam->new(
    alpha => 'A',
    _beta => 'B',
    gamma_ => 'G',
);
is_deeply [$object->values], ['A', 'B', 'G'],
    ':param strips exactly one leading underscore from the field name';

done_testing;
