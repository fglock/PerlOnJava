use strict;
use warnings;
use Test::More tests => 4;

use Package::Stash;

my $stash = Package::Stash->new('PackageStashGetOrAdd');
my $scalar = $stash->get_or_add_symbol('$value');
my $array  = $stash->get_or_add_symbol('@items');

ok(ref($scalar) eq 'SCALAR', 'get_or_add_symbol vivifies scalar slot');
ok(ref($array) eq 'ARRAY', 'get_or_add_symbol vivifies array slot');

$$scalar = 'set through returned slot';
push @$array, 'item';

is($PackageStashGetOrAdd::value, 'set through returned slot',
   'returned scalar is the package slot');
is_deeply(\@PackageStashGetOrAdd::items, ['item'],
          'returned array is the package slot');

