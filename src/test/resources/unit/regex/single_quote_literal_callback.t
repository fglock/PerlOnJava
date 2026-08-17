use strict;
use warnings;
use Test::More tests => 10;

our $out = 1;
ok('abc' =~ m'a(?{ $out = 2 })b', 'literal callback pattern matches');
is($out, 2, 'literal callback executes without use re eval');

$out = 1;
ok(!('abc' =~ m'a(?{ $out = 3 })c'), 'failed literal callback branch does not match');
is($out, 1, 'failed literal callback branch unwinds state');

my $name = 'expanded';
ok('expanded' !~ m'^$name$', 'single-quote regex delimiter does not interpolate variables');

$out = 4;
ok('a' =~ m'a(?{ $out++ })', 'callback code still resolves variables');
is($out, 5, 'callback variable update is visible');

ok('abc' =~ m'^a(??{"b"})c$', 'single-quote delimiter retains literal recursive callback');

my $quoted = qr'^$name$';
ok('expanded' !~ $quoted, 'single-quote qr delimiter does not interpolate variables');

my ($search, $subject) = ('x', 'x');
$subject =~ s'$search'y';
is($subject, 'x', 'single-quote substitution pattern does not interpolate variables');
