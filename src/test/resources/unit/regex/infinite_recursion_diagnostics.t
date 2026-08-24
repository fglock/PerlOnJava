use strict;
use warnings;
use Test::More;

my @cases = (
    ['lookahead left recursion', q{q(a) =~ /(.(?2))((?<=(?=(?1)).))/}],
    ['whole-pattern left recursion', q{q(aa) =~ /(?R)a/}],
    ['inter-cyclic optional recursion',
        q{q(bbaa) =~ /(?&x)(?(DEFINE)(?<x>(?&y)*a)(?<y>(?&x)*b))/}],
    ['optional left recursion', q{q(abc) =~ /a((?1)?)c/}],
    ['minimal optional left recursion', q{q(abc) =~ /a((?1)??)c/}],
    ['star left recursion', q{q(abc) =~ /a((?1)*)c/}],
    ['plus left recursion', q{q(abc) =~ /a((?1)+)c/}],
    ['bounded left recursion', q{q(abc) =~ /a((?1){0,3})c/}],
);

for my $case (@cases) {
    my ($name, $source) = @$case;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $value = eval $source;
    ok(!defined($value), "$name dies");
    like($@, qr/^Infinite recursion in regex at /,
        "$name uses Perl's diagnostic identity");
    is(scalar(@warnings), 0, "$name does not warn before dying");
}

$@ = 'sentinel';
ok(q(aaabbb) =~ /a(?R)?b/, 'productive optional self recursion still matches');
is($@, 'sentinel', 'productive recursion preserves the existing error scalar');

done_testing;
