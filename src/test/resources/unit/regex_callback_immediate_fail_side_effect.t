use strict;
use warnings;
use Test::More;

my $value = 1;
ok !('a' =~ /a(?{ $value = 3 })(?!)/),
    'an immediate negative assertion fails the match';
is $value, 3,
    'callback assignment survives an immediate zero-width failure';

done_testing;
