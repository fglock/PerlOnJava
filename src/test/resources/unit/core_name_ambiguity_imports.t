use strict;
use warnings;
no warnings 'once';
use Test::More tests => 10;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    require Data::Dump;
    require Try::Tiny;
    my $ok = eval q{
        package CoreNameAmbiguityImports;
        use warnings;
        use Try::Tiny;
        our $value = try { 42 } catch { 0 };
        1;
    };
    ok($ok, 'Try::Tiny consumer compiles');
    my $time_ok = eval q{
        package CoreNameAmbiguityImportedTime;
        use warnings;
        use Time::HiRes qw(time);
        our $value = time();
        1;
    };
    ok($time_ok, 'Time::HiRes consumer compiles');
}

is($CoreNameAmbiguityImports::value, 42, 'imported try and catch run');
is(Data::Dump::dump([1]), '[1]', 'Data::Dump dump runs');
unlike(join('', @warnings), qr/Ambiguous call resolved as CORE::(?:try|catch)\(\)/,
    'Try::Tiny imports produce no CORE ambiguity warning');
unlike(join('', @warnings), qr/Ambiguous call resolved as CORE::dump\(\)/,
    'Data::Dump declaration produces no CORE ambiguity warning');
unlike(join('', @warnings), qr/Ambiguous call resolved as CORE::time\(\)/,
    'imported Time::HiRes time produces no CORE ambiguity warning');

my $ambiguity = '';
{
    local $SIG{__WARN__} = sub { $ambiguity .= shift };
    eval 'package CoreNameAmbiguityOverride; use warnings; BEGIN { *time = sub { 5 } } time();';
}
like($ambiguity, qr/Ambiguous call resolved as CORE::time\(\)/,
    'genuine CORE ambiguity still warns');

my $forward_warning = '';
{
    local $SIG{__WARN__} = sub { $forward_warning .= shift };
    eval 'package CoreNameAmbiguityForward; use warnings; sub time; time();';
}
like($forward_warning, qr/Ambiguous call resolved as CORE::time\(\)/,
    'an ordinary forward declaration still warns');

my $subs_warning = '';
{
    local $SIG{__WARN__} = sub { $subs_warning .= shift };
    eval 'package CoreNameAmbiguitySubsPragma; use warnings; use subs qw(time); sub time { 5 } time();';
}
unlike($subs_warning, qr/Ambiguous call resolved as CORE::time\(\)/,
    'use subs explicitly resolves the core name');
