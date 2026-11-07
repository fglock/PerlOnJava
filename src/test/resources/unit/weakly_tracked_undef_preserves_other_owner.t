use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

my $target = [];
my @owners = ($target, $target);
my $weak = $target;
weaken($weak);
undef $target;

undef $owners[0];
ok(defined $weak, 'one remaining strong cell keeps the weak target alive');

undef $owners[1];
ok(!defined $weak, 'the final strong cell releases the weak target');
