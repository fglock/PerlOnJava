use strict;
use warnings;
use utf8;
use Test::More;

my $word = 'über';
ok($word =~ /^\büber\b$/, 'Unicode letter is enclosed by word boundaries');
ok($word =~ /ü\Bber/, 'Unicode letters have no internal word boundary');
ok($word =~ /^\w+$/, 'Unicode letter remains a word character');
ok($word !~ /(?a:^\büber\b$)/, 'ASCII mode retains ASCII-only word boundaries');

my %lower_to_upper = eval q[use utf8;
    my %lower_to_upper = ('über maus' => 'Über Maus');
    return %lower_to_upper;
];
my ($title) = keys %lower_to_upper;
ok($title =~ /^\büber\b/, 'returned UTF-8 hash key has a leading word boundary');
ok($title =~ /ü\Bber/, 'returned UTF-8 hash key has no internal word boundary');
$title =~ s/\b(.*?)\b/$1 eq uc $1 ? $1 : "\u\L$1"/ge;
is($title, $lower_to_upper{'über maus'},
        'Unicode word boundaries drive evaluated substitution');

done_testing;
