use strict;
use warnings;
use Test::More;

my $literal = v1.2.3;

package PackageVstringVersionObject v1.2.3;

package main;

is(ref($PackageVstringVersionObject::VERSION), 'version',
    'a package v-string declaration installs a version object');
ok($literal eq $PackageVstringVersionObject::VERSION,
    'the installed version object string-compares equal to its v-string literal');
is("$PackageVstringVersionObject::VERSION", 'v1.2.3',
    'the installed version object preserves dotted v-string spelling');

my $invalid = eval 'package PackageVstringInvalid v01.02.03; 1';
ok(!$invalid, 'a package v-string component cannot have a leading zero');
like($@, qr/no leading zeros/,
    'a malformed package v-string reports Perl\'s leading-zero diagnostic');

done_testing;
