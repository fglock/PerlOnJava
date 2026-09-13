use strict;
use warnings;
use Test::More;

my $text = 'ab42cd42';
my @published;
while ($text =~ /42/g) {
    push @published, [ $&, [ @- ], [ @+ ], pos($text) ];
}

is_deeply(
    \@published,
    [
        [ '42', [ 2 ], [ 4 ], 4 ],
        [ '42', [ 6 ], [ 8 ], 8 ],
    ],
    'captureless /g publishes whole-match offsets and advances pos',
);

ok(!($text =~ /never/), 'later failed match is false');
ok(!defined($&), 'failed match clears the captureless whole match');
is_deeply([ @- ], [], 'failed match clears captureless start offsets');
is_deeply([ @+ ], [], 'failed match clears captureless end offsets');

my @captureless_list = 'ab42cd42' =~ /42/g;
is_deeply(\@captureless_list, [ '42', '42' ],
    'captureless /g returns whole matches in list context');

my $captured = 'x7';
ok($captured =~ /(7)/, 'numbered capture still matches');
is($1, '7', 'numbered capture remains published');
is_deeply([ @- ], [ 1, 1 ], 'numbered capture start offsets remain published');
is_deeply([ @+ ], [ 2, 2 ], 'numbered capture end offsets remain published');

my @captured_list = 'a1b2' =~ /(\d)/g;
is_deeply(\@captured_list, [ '1', '2' ],
    'captured /g returns captured groups in list context');

done_testing;
