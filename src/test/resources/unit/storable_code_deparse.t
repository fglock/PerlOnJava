use strict;
use warnings;
use Test::More tests => 2;
use Storable qw(freeze);

my $code = sub { return 42 };
local $Storable::Deparse = 1;
my $frozen = eval { freeze($code) };

ok(!$@, 'Storable can freeze a code reference with Deparse enabled');
ok(defined($frozen) && length($frozen), 'the frozen code reference is non-empty');
