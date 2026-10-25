use strict;
use warnings;
use Test::More;

my $list_start = qr/^(?=[\*\#]+\s)/msix;
my $list_end = qr/(?:^(?![\*\#\s])|\z)/msix;
my $subject = "prefix\n\n----\n\n* list 1\n** list 1.1\n*# list 1.I\n"
    . "*** list 1.2.1\n* list 2\n* list 3\n\n----\n";

ok($subject =~ m{$list_start(.*?)$list_end}msix,
    'multiline lookahead finds a list after preceding text');
is($1, "* list 1\n** list 1.1\n*# list 1.I\n*** list 1.2.1\n"
        . "* list 2\n* list 3\n\n",
    'lazy capture stops at the first non-list line');

my $single_line = "prefix\n* only item\nend\n";
ok($single_line =~ m{$list_start(.*?)$list_end}msix,
    'multiline lookahead also matches a single list line');
is($1, "* only item\n", 'single-line capture has the expected boundary');

my $no_list = "prefix\nplain text\nend\n";
ok($no_list !~ m{$list_start(.*?)$list_end}msix,
    'multiline lookahead rejects text without a list line');

done_testing;
