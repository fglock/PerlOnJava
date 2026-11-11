#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 8;

{
    package Local::SyslogOpenlogImport;
    use Sys::Syslog qw(openlog);
}

ok(defined(&Local::SyslogOpenlogImport::openlog),
    'openlog is imported into an independent package');
is(Local::SyslogOpenlogImport::openlog('perl-test', '', 'user'), 1,
    'the explicitly imported openlog function is callable');

my $loaded = eval q{
    package Local::SyslogDynamicImport;
    use Sys::Syslog;
    sub call_openlog { openlog('perl-test', '', 'user') }
    1;
};
ok($loaded, 'Sys::Syslog imports while dynamically loading a package');
ok(defined(&Local::SyslogDynamicImport::openlog),
    'dynamic loading installs openlog in its caller package');
is(Local::SyslogDynamicImport::call_openlog(), 1,
    'a subroutine compiled during dynamic loading can call openlog');

use lib 'src/test/resources/unit/fixtures/syslog';
my $fixture_loaded = eval q{
    package Local::SyslogFixtureImport;
    use Sys::Syslog;
    sub call_openlog { openlog() }
    1;
};
ok($fixture_loaded, 'a package-less Sys::Syslog fixture loads inside the importing package');
ok(defined(&Local::SyslogFixtureImport::openlog),
    'functions from a package-less module are defined in the importing package');
is(Local::SyslogFixtureImport::call_openlog(), 1,
    'the importing package can call a function from a package-less module');
