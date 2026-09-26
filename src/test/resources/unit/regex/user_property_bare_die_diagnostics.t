use strict;
use warnings;
use utf8;
use Test::More;

our ($overflow_property, $bare_die_property);
BEGIN {
    $overflow_property = qr/\p{IsUnitOverflow}/;
    $bare_die_property = qr/\p{IsUnitBareDie}/;
}

sub IsUnitOverflow {
    return "0\t80000000000000000#ネ";
}

my $ok = eval { 'A' =~ $overflow_property; 1 };
like($@,
    qr/Code point too large in "0\t80000000000000000#ネ" in expansion of main::IsUnitOverflow/,
    'user property parse diagnostics preserve Unicode text and qualify bare names');

sub IsUnitBareDie {
    die;
}

$ok = eval { 'A' =~ $bare_die_property; 1 };
like($@, qr/Died.*in expansion of main::IsUnitBareDie/s,
    'bare user property die uses Perl expansion wording');

done_testing;
