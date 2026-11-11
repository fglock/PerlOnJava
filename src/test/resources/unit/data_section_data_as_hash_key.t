use strict;
use warnings;
use Test::More;

my $out = {};
$out->{__DATA__} = 1;
is($out->{__DATA__}, 1, '__DATA__ is an ordinary hash key in an expression');

is(<DATA>, "payload after real marker\n", 'a real __DATA__ marker still populates DATA');

done_testing;

__DATA__
payload after real marker
