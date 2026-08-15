use strict;
use warnings;
use Test::More;

our $localized = 'outer';
my $executions = 0;
my @seen;
my $rx = qr/(a)(?{
    ++$executions;
    push @seen, "$1:$localized";
    local $localized = 'inside';
    17;
})(b|c)/;

is($executions, 0, 'plain callback is not executed while qr is constructed');
ok('ac' =~ $rx, 'plain callback pattern matches');
is($executions, 1, 'plain callback executes at match time');
is_deeply(\@seen, ['a:outer'], 'callback sees provisional captures and lexical state');
is($^R, 17, 'last successful plain callback publishes $^R');
is($localized, 'outer', 'callback local scope unwinds after a successful match');
my $embedded = qr/(?:)$rx(?:)/;
is($executions, 1, 'embedding a callback regex does not execute its closure');
ok('ab' =~ $embedded, 'embedded callback regex matches');
is($executions, 2, 'embedded regex retains its callback table');

my $literal_call_id = qr/CALL:999(?{ ++$executions })/;
my $embedded_literal_call_id = qr/$literal_call_id/;
ok('CALL:999' =~ $embedded_literal_call_id,
    'single-segment embedding remaps only internal callout markers');
is($executions, 3, 'literal callout-like text does not corrupt the callback table');

@seen = ();
my $backtracking = qr/
    a(?{ local $localized = 'branch'; push @seen, 'first'; 11 })b
  |
    a(?{ push @seen, $localized; 23 })c
/x;
ok('ac' =~ $backtracking, 'engine backtracks across callbacks');
is_deeply(\@seen, ['first', 'outer'], 'backtracking restores callback local scope');
is($^R, 23, 'backtracked callback result does not replace successful $^R');

my $condition_calls = 0;
ok('ab' =~ /a(?(?{ ++$condition_calls; 1 })b|c)/,
    'true callback condition selects yes branch');
ok('ac' =~ /a(?(?{ ++$condition_calls; 0 })b|c)/,
    'false callback condition selects no branch');
is($condition_calls, 2, 'each callback condition executes once');

my $literal_iterations = 0;
while ("\x{100}bc" =~ /(..?)(?{ $^N })/g) {
    ++$literal_iterations;
    last if $literal_iterations > 2;
}
is($literal_iterations, 2, 'literal target preserves /g position across callback matches');

done_testing();
