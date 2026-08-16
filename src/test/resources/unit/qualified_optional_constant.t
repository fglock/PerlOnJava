use strict;
use warnings;
use Test::More tests => 1;

my $has_optional = 0;
my $value = $has_optional
    ? Optional::Constants::OPTIONAL_VALUE
    : 'fallback';

is($value, 'fallback', 'guarded optional fully-qualified constant parses');
