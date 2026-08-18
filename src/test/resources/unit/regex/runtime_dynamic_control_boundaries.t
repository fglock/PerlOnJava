use strict;
use warnings;
use re 'eval';
use Test::More tests => 9;

our ($count, $REGERROR);

{
    local $REGERROR = 'sentinel';
    my $dynamic = 'a';
    'a' =~ /(??{$dynamic})/;
    is($REGERROR, 'sentinel',
        'dynamic patterns without control verbs leave REGERROR untouched');
}

for my $case (
    [PRUNE  => 3],
    [SKIP   => 1],
    [COMMIT => 1],
    [THEN   => 3],
) {
    my ($verb, $expected_count) = @$case;
    local $REGERROR;
    $count = 0;
    my $dynamic = "(*$verb)";

    'aaab' =~ /a+b?(??{$dynamic})(?{$count++})(*FAIL)/;

    is($count, $expected_count,
        "dynamic $verb propagates its cut to the enclosing matcher");
    is($REGERROR, '1',
        "dynamic $verb publishes its enclosing failure in REGERROR");
}
