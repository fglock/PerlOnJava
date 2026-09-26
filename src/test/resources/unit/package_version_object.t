use strict;
use warnings;
use Test::More tests => 5;

package PackageVersion::Object v1.2.3;

package PackageVersion::Padded v0.19.045;

package main;

is(ref $PackageVersion::Object::VERSION, 'version',
    'package declaration creates a version object');
ok($PackageVersion::Object::VERSION eq v1.2.3,
    'package version compares equal to a bare v-string');
is("$PackageVersion::Object::VERSION", 'v1.2.3',
    'package version stringifies to its declaration');
is("$PackageVersion::Padded::VERSION", 'v0.19.045',
    'package version retains padded dotted spelling');

ok(version->new('v1.2.3') eq v1.2.3,
    'version object compares equal to a bare v-string');
