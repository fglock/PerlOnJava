use strict;
use warnings;
use Test::More;

my $two_spaces = "  ";
ok($two_spaces =~ /\A\s*\x20\z/,
    'greedy whitespace star backtracks one literal space');
ok($two_spaces =~ /\A\s+\x20\z/,
    'greedy whitespace plus backtracks one literal space');

my $two_tabs = "\t\t";
ok($two_tabs =~ /\A\s*\t\z/,
    'greedy whitespace star backtracks one literal tab');

my $long = 'BEGIN' . (' ' x 20_000) . 'END';
ok($long =~ /\ABEGIN\s+END\z/,
    'long greedy whitespace plus reaches its terminator');
ok($long =~ /\ABEGIN\s*END\z/,
    'long greedy whitespace star reaches its terminator');
ok($long =~ /\ABEGIN\s+?END\z/,
    'long lazy whitespace plus reaches its terminator');
ok($long =~ /\ABEGIN\s*?END\z/,
    'long lazy whitespace star reaches its terminator');

done_testing;
