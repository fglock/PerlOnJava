use strict;
use warnings;
use Test::More;

ok(UNIVERSAL->can('import'), 'UNIVERSAL has import');
ok(UNIVERSAL->can('unimport'), 'UNIVERSAL has unimport');
is(eval { UNIVERSAL->import; 1 }, 1, 'empty UNIVERSAL import succeeds');
my $ok = eval { UNIVERSAL->import('can'); 1 };
ok(!$ok, 'UNIVERSAL cannot export a requested symbol');
like($@, qr/^UNIVERSAL does not export anything/, 'UNIVERSAL import error matches Perl');

{
    package Issue1214::InfoSysFreeDBEntry;
    package Issue1214::IPCFileSpec;
    package Issue1214::HTTPRecorder;
    package Issue1214::FindLibFixture;
    package Issue1214::DeviceFirmataBase;
    sub import { shift->SUPER::import(@_) }
}

my @missing_import_cases = (
    ['Issue1214::InfoSysFreeDBEntry', 'Loaded InfoSys::FreeDB::Entry'],
    ['Issue1214::IPCFileSpec', 'devnull'],
    ['Issue1214::HTTPRecorder', 'Loaded HTTP::Recorder'],
    ['Issue1214::FindLibFixture', qw(a 1 b 42)],
    ['Issue1214::DeviceFirmataBase', 'LiquidCrystal_I2C'],
);

for my $case (@missing_import_cases) {
    my ($package, @imports) = @$case;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };
    my $ok = eval { $package->import(@imports); 1 };
    is($ok, 1, "$package inherited import with arguments is non-fatal");
    is($@, '', "$package inherited import leaves no exception");
    like($warnings[0], qr/^Attempt to call undefined import method with arguments /,
         "$package inherited import emits Perl's missing-import warning");
    like($warnings[0], qr/\Qvia package "$package"\E/,
         "$package warning identifies the missing importer");
}

done_testing;
