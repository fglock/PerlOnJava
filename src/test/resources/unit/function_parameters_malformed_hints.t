use strict;
use warnings;
use Test::More tests => 4;
use Function::Parameters;

my @warnings;
BEGIN {
    $SIG{__WARN__} = sub { push @warnings, $_[0]; };
}

sub take_warnings { splice @warnings }

{
    BEGIN { $^H{'Function::Parameters/config'} .= ''; }
    if (0) { }
}
BEGIN {
    my @warnings = take_warnings();
    is(scalar @warnings, 1, 'malformed hint warns once');
    like($warnings[0], qr{^Function::Parameters: \$\^H\{'Function::Parameters/config'\} is not a reference; skipping: HASH\(},
        'warning identifies the malformed configuration');
}

{
    no warnings 'Function::Parameters';
    BEGIN { $^H{'Function::Parameters/config'} .= ''; }
    if (0) { }
    if (0) { }
}
BEGIN {
    my @warnings = take_warnings();
    is(scalar @warnings, 0, 'category suppression disables malformed-hint warning');
    is($warnings[0], undef, 'suppressed warning queue is empty');
}
