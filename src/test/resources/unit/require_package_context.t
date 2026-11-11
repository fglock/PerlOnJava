use strict;
use warnings;
use Test::More tests => 3;

my $loaded = eval q{
    BEGIN {
        package Local::RequirePackageContext;
        require './src/test/resources/unit/fixtures/syslog/Sys/Syslog.pm';
    }
    1;
};

ok($loaded, 'require succeeds inside a non-main package');
ok(defined(&Local::RequirePackageContext::openlog),
    'a package-less required file compiles in the requiring package');
is(Local::RequirePackageContext::openlog(), 1,
    'the requiring package can call the loaded function');
