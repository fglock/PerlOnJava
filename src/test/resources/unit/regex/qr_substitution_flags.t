use strict;
use warnings;
use Test::More;

my $strip = qr{\(registry time\)\z};
my $date = '2011-04-26 00:00:00 (registry time)';
$date =~ s{$strip}{}mx;
is($date, '2011-04-26 00:00:00 ', 's///x preserves spaces inside compiled qr pattern');

my $case = qr{foo};
my $subject = 'FOO';
$subject =~ s{$case}{bar}i;
is($subject, 'FOO', 's///i does not add case-insensitive matching to compiled qr');

my $anchored = qr{^a$};
my $lines = "a\nb";
$lines =~ s{$anchored}{X}m;
is($lines, "a\nb", 's///m does not add multiline matching to compiled qr');

my $global = qr{a};
my $text = 'aa';
$text =~ s{$global}{X}g;
is($text, 'XX', 's///g still applies globally with compiled qr');

my $copy = 'a';
my $replaced = $copy =~ s{$global}{X}r;
is($copy, 'a', 's///r leaves source unchanged with compiled qr');
is($replaced, 'X', 's///r returns replaced copy with compiled qr');

done_testing;
