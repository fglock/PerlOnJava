use strict;
use warnings;
use Test::More;
use version;

local $SIG{__WARN__} = sub { };
my $infinity = version->new('v9223372036854775807');
my $finite   = version->new('v999.0.0');

is($infinity <=> $infinity, 0, 'v.Inf compares equal to itself');
ok($infinity > $finite, 'v.Inf compares greater than finite versions');

done_testing;
