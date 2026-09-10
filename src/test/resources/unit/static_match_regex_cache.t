use strict;
use warnings;
use Test::More tests => 3;

my $text = 'a1b2';
my @digits;
push @digits, $1 while $text =~ /(\d)/g;
is_deeply(\@digits, [1, 2], 'a static /g match retains capture and target position');

my $first = 'left-17' =~ /([a-z]+)-(\d+)/;
is($1 . ':' . $2, 'left:17', 'a static match updates captures');

my $second = 'right-2048' =~ /([a-z]+)-(\d+)/;
ok($first && $second && $1 eq 'right' && $2 eq '2048',
    'a later static match replaces captures');
