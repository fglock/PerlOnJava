use strict;
use warnings;

use Test::More tests => 8;

my $value = 'abc';
pos($value) = length($value);
ok($value =~ /\G\s*/gc,
   'first pattern can match zero characters at the global position');
is(pos($value), 3, 'terminal zero-length match preserves pos');
ok($value !~ /\G\z/gc,
   'different pattern cannot repeat a zero-length global match at the same position');
is(pos($value), 3, '/c preserves pos after the rejected zero-length retry');

pos($value) = 1;
ok($value =~ /\G(?=b)/gc, 'zero-width lookahead matches at an interior position');
ok($value !~ /\G(?=b)/gc,
   'same pattern cannot repeat a zero-length global match at the same position');
is(pos($value), 1, 'interior rejected retry preserves pos with /c');
ok($value =~ /\Gb/gc,
   'a consuming match remains available after a rejected zero-length retry');
