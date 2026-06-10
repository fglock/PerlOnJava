use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(blessed reftype);

my $io = *STDOUT{IO};

is(reftype($io), 'IO', 'STDOUT IO slot has IO reftype');
ok(blessed($io), 'STDOUT IO slot is blessed');
ok(ref($io), 'STDOUT IO slot has a ref class');
