use strict;
use warnings;
use Test::More;

my $mac_systemdirectory_loaded = eval {
    require Mac::SystemDirectory;
    Mac::SystemDirectory->import(qw(
        FindDirectory HomeDirectory TemporaryDirectory
        NSAllDomainsMask NSApplicationDirectory NSDesktopDirectory
        NSUserDomainMask
    ));
    1;
};
plan skip_all => 'Mac::SystemDirectory required'
    unless $mac_systemdirectory_loaded;
plan tests => 13;

pass 'Mac::SystemDirectory loaded';

eval { FindDirectory() };
like($@, qr/^Usage: /, 'FindDirectory requires a directory argument');

eval { FindDirectory(0, 0, 0) };
like($@, qr/^Usage: /, 'FindDirectory rejects too many arguments');

eval { HomeDirectory(0) };
like($@, qr/^Usage: /, 'HomeDirectory rejects arguments');

eval { TemporaryDirectory(0) };
like($@, qr/^Usage: /, 'TemporaryDirectory rejects arguments');

my $home = eval { HomeDirectory() };
is($@, '', 'HomeDirectory lives');
ok(defined($home) && length($home), 'HomeDirectory returns a path');

my $tmp = eval { TemporaryDirectory() };
is($@, '', 'TemporaryDirectory lives');
ok(defined($tmp) && length($tmp), 'TemporaryDirectory returns a path');

my $application_dir = eval { FindDirectory(NSApplicationDirectory()) };
is($@, '', 'FindDirectory lives');
ok(defined($application_dir) && length($application_dir), 'FindDirectory returns a scalar path');

my @application_dirs = FindDirectory(NSApplicationDirectory(), NSAllDomainsMask());
ok(@application_dirs >= 1, 'FindDirectory returns paths in list context');

is(Mac::SystemDirectory::NSUserDomainMask(), 1, 'domain mask constant is available');
