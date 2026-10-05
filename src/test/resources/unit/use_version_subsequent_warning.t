use strict;
use warnings;
use Test::More;

my (@warnings, $ok);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval q{
        use warnings;
        use v5.12;
        use v5.20;
        1;
    };
}
ok($ok, 'different in-scope versions remain allowed before Perl 5.46');
like(join('', @warnings), qr/Changing use VERSION while another use VERSION is in scope is deprecated/,
    'modern version changes issue the deprecated warning');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval q{
        no warnings 'deprecated::subsequent_use_version';
        use v5.12;
        use v5.20;
        1;
    };
}
ok($ok, 'disabling the warning leaves the version change allowed');
is(scalar @warnings, 0, 'the warning category can be disabled');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval q{
        use warnings FATAL => 'deprecated::subsequent_use_version';
        use v5.12;
        use v5.20;
        1;
    };
}
ok(!$ok, 'making the deprecation fatal rejects a different version');
like($@, qr/Changing use VERSION while another use VERSION is in scope is deprecated/,
    'fatal warning preserves the Perl diagnostic');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval q{
        use 5.006;
        use v5.20;
        1;
    };
}
ok($ok, 'legacy decimal use VERSION can advance to a later feature bundle');
is(scalar @warnings, 0, 'legacy decimal version advancement does not warn');

done_testing;
