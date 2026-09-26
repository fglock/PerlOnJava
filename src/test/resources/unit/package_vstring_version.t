use strict;
use warnings;
use Test::More tests => 4;

my $ok = eval q{
    package PackageVstringVersion;
    package PackageVstringVersion v1.2.3;
    v1.2.3 eq $PackageVstringVersion::VERSION;
};
ok($ok, 'package v-string VERSION compares equal to its bare v-string literal');

my $zero_ok = eval q{
    package PackageVstringZeroVersion;
    package PackageVstringZeroVersion v0.0.0;
    v0.0.0 eq $PackageVstringZeroVersion::VERSION;
};
ok($zero_ok, 'package v-string VERSION preserves all-zero components');

my $padded_ok = eval q{
    package PackageVstringPaddedVersion;
    package PackageVstringPaddedVersion v0.19.045;
    v0.19.045 eq $PackageVstringPaddedVersion::VERSION;
};
ok($padded_ok, 'package v-string VERSION preserves zero-padded later components');

eval q{ package PackageVstringInvalid v01.02.03; 1 };
like($@, qr/no leading zeros/, 'package v-string rejects zero-padded components');
