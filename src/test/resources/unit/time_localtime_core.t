use strict;
use warnings;
use Test::More;
use Time::localtime;

my $epoch = localtime(0);
is($epoch->sec, 0, 'Time::localtime exposes named fields');

done_testing;
