use strict;
use warnings;
use Test::More tests => 3;

my $pattern = qr/\A(?:(a|b\1)??c)+\z/;
my $subject = 'acbac';
ok($subject =~ $pattern, 'lazy optional self-reference matches the whole subject');
is($1, 'ba', 'backtracking restores the capture from the winning iteration');

my $near_miss = 'acbxc';
ok($near_miss !~ $pattern, 'a failed self-reference does not produce a match');
