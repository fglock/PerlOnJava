use strict;
use warnings;
use Test::More;

use Devel::Size qw(size total_size);

ok(size(1) > 0, 'size returns a positive estimate');
ok(total_size(1) >= size(1), 'total_size includes the root object');
ok(size('a much longer string value') > size('x'),
   'size accounts for scalar string payload');

my $small = [1];
my $large = [1 .. 100];
ok(total_size($large) > total_size($small), 'aggregate size grows with contents');

my $small_hash = { value => 'x' };
my $large_hash = { value => 'x' x 1000 };
ok(total_size($large_hash) > total_size($small_hash),
   'hash size grows with contained data');

my $shared = [1 .. 20];
my $twice = [$shared, $shared];
my $once = [$shared];
ok(total_size($twice) < 2 * total_size($once), 'shared referent is deduplicated');

my $cycle = [];
push @$cycle, $cycle;
ok(total_size($cycle) > 0, 'cyclic graph terminates');

use Devel::Size qw(:all);
ok(defined &Devel::Size::size && defined &Devel::Size::total_size,
   ':all exposes both functions');

done_testing();
