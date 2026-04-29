use strict;
use warnings;
use Test::More tests => 12;

# Regression: parsing of a package-stash variable like %Foo:: must NOT
# consume a following whitespace-separated keyword (and / or / not / xor /
# cmp / eq / ne / lt / gt / le / ge / x) as part of the identifier.
#
# Real perl tokenizes "%Foo:: and 2" as the stash hash %Foo:: followed by
# the low-precedence operator "and"; only "%Foo::and" (no space) is the
# hash named "and" in package Foo. PerlOnJava previously skipped whitespace
# after :: and accidentally produced %Foo::and, causing a syntax error and
# (because the bundled Dumpvalue.pm uses `and %overload:: and ...`) breaking
# any code path through CPAN.pm's error reporter.
# See https://github.com/fglock/PerlOnJava/issues for the cpan -t JSON::Literal
# repro that surfaced this.

package Foo;
our $touched = 0;

package main;

# Make sure the stash exists before we read it.
$Foo::dummy = 1;

# 1: bare stash hash followed by `and` operator
my $r1 = (%Foo:: and 1);
is($r1, 1, '%Foo:: and 1  (whitespace before "and" must keep "and" as operator)');

# 2: bare stash hash followed by `or` operator
my $r2 = (%Foo:: or 'fallback');
ok($r2, '%Foo:: or ...   (whitespace before "or" must keep "or" as operator)');

# 3: bare stash hash followed by `not` is just illegal-as-statement in perl,
#    but `! %Foo:: and 1` and `not %Foo:: and 1` parse fine.
my $r3 = (not %Foo::) ? 0 : 1;
is($r3, 1, 'not %Foo::      (whitespace after :: before nothing parses)');

# 4..7: same with the other low-precedence keyword operators
my $r4 = (%Foo:: xor 0);
ok($r4, '%Foo:: xor 0');

my $r5 = (1 and %Foo:: and 2);
is($r5, 2, '1 and %Foo:: and 2');

my $r6 = (1 and %Foo::);
ok($r6, '1 and %Foo::    (trailing stash with no following operator)');

my $r7 = (%Foo:: && 1);
is($r7, 1, '%Foo:: && 1     (high-precedence form, regression check)');

# 8: comparison operators must also stay as operators
my @keys = sort keys %Foo::;
ok(@keys, 'keys %Foo:: returns something');
ok((scalar(@keys) cmp 0) >= 0, 'scalar(keys %Foo::) cmp 0');

# 9: %Foo::and (no space) should still be the hash named "and" in Foo
%Foo::and = (a => 1);
is(scalar(keys %Foo::and), 1, '%Foo::and (no space) is still hash named "and" in Foo');

# 10: $Foo::or (no space) is the scalar named "or" in Foo
$Foo::or = 'value';
is($Foo::or, 'value', '$Foo::or (no space) is still scalar named "or" in Foo');

# 11: control case — Dumpvalue's exact pattern from line 110 must compile
eval q{
    my $self = { bareStringify => 1 };
    my $val = "x";
    no strict 'refs';
    $val = &{'overload::StrVal'}($val)
        if $self->{bareStringify} and ref \$val
        and %overload:: and defined &{'overload::StrVal'};
    1;
} or do {
    fail("Dumpvalue.pm pattern compiles: $@");
};
pass("Dumpvalue.pm pattern (and %overload:: and defined ...) compiles cleanly");
