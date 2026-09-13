use strict;
use warnings;
use Test::More;

if ($^X !~ m{(?:^|/)jperl(?:\z|\s)}) {
    plan skip_all => 'system Perl predates experimental::equ';
}

my $warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= shift };
    eval q{ use warnings 'experimental::equ'; my ($x, $y); $x === $y; 'a' equ 'a'; };
}
like($warnings, qr/The '===\' operator is experimental/, '=== warns in enabled scope');
like($warnings, qr/The 'equ\' operator is experimental/, 'equ warns in enabled scope');

$warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= shift };
    eval q{ no warnings 'experimental::equ'; my ($x, $y); $x !== $y; 'a' neu 'b'; };
}
is($warnings, '', 'no warnings suppresses operator warnings');

my $count = 0;
my $middle = sub { ++$count; return undef };
my $single = eval q{$middle->() === undef};
ok($single, 'defined numeric equality handles undef');
is($count, 1, 'comparison evaluates an operand once');

$count = 0;
my $chain = eval q{1 === $middle->() === undef};
is($count, 1, 'chained comparison evaluates its middle operand once');
ok(!$chain, 'chained defined comparison preserves short-circuit semantics');

done_testing;
