use strict;
use warnings;
use Test::More tests => 4;

my $failed_side_effect = 0;
ok('ac' !~ /a(?(?{ $failed_side_effect = 1; 1 })b|c)/,
    'callback path can be abandoned by a wholly failed match');
is($failed_side_effect, 1,
    'ordinary callback mutation persists after the whole match fails');

my $exception_side_effect = 0;
my $error = '';
eval {
    'a' =~ /a(?{
        $exception_side_effect = 1;
        die "callback exploded\n";
    })/;
    1;
} or $error = $@;
like($error, qr/callback exploded/,
    'callback exception crosses the matcher boundary');
is($exception_side_effect, 1,
    'ordinary callback mutation before an exception persists');
