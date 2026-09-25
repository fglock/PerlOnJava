use strict;
use warnings;
use Test::More tests => 2;

my $ok = eval q{
    package PackageVstringVersion;
    package PackageVstringVersion v1.2.3;
    v1.2.3 eq $PackageVstringVersion::VERSION;
};
ok($ok, 'package v-string VERSION compares equal to its bare v-string literal');

eval q{ package PackageVstringInvalid v01.02.03; 1 };
like($@, qr/no leading zeros/, 'package v-string rejects zero-padded components');
