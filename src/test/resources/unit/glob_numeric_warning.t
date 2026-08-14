use strict;
use warnings;
no warnings 'once';

print "1..6\n";

my $warning = '';
local $SIG{__WARN__} = sub { $warning .= $_[0] };
*target = undef;
print $warning =~ /Undefined value assigned to typeglob/
    ? "ok 1 - assigning undef to a typeglob warns\n"
    : "not ok 1 - assigning undef to a typeglob warns ($warning)\n";

my $glob = *TARGET;
for my $case ([d => 0], [u => 0], [e => qr/^0\.0+e\+?00$/i]) {
    $warning = '';
    my ($format, $expected) = @$case;
    my $got = sprintf "%$format", $glob;
    my $value_ok = ref($expected) ? $got =~ $expected : $got eq $expected;
    my $number = $format eq 'd' ? 2 : $format eq 'u' ? 3 : 4;
    print $value_ok && $warning =~ /isn't numeric in sprintf/
        ? "ok $number - bare glob warns and numifies to zero for %$format\n"
        : "not ok $number - bare glob warns and numifies to zero for %$format ($got; $warning)\n";
}

{
    no warnings 'numeric';
    $warning = '';
    my $got = sprintf '%d', $glob;
    print $got eq '0' && $warning eq ''
        ? "ok 5 - no warnings suppresses the numeric glob warning\n"
        : "not ok 5 - no warnings suppresses the numeric glob warning ($got; $warning)\n";
}

$warning = '';
my $got = sprintf '%d', $glob;
print $got eq '0' && $warning =~ /isn't numeric in sprintf/
    ? "ok 6 - numeric glob warning is restored after lexical scope\n"
    : "not ok 6 - numeric glob warning is restored after lexical scope ($got; $warning)\n";
