#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 12;

BEGIN {
    require XSLoader;
    XSLoader::load('Sys::Syslog', '0.36');
}

is(Sys::Syslog::LOG_INFO(), 6, 'LOG_INFO constant is available');
is(Sys::Syslog::LOG_LOCAL0(), 128, 'LOG_LOCAL0 constant is available');

my $priority = Sys::Syslog::LOG_LOCAL0() | Sys::Syslog::LOG_INFO();
is(Sys::Syslog::LOG_PRI($priority), 6, 'LOG_PRI extracts priority');
is(Sys::Syslog::LOG_FAC($priority), 16, 'LOG_FAC extracts facility index');
is(Sys::Syslog::LOG_MAKEPRI(16, 6), $priority, 'LOG_MAKEPRI combines facility and priority');
is(Sys::Syslog::LOG_MASK(Sys::Syslog::LOG_INFO()), 64, 'LOG_MASK matches syslog macro');
is(Sys::Syslog::LOG_UPTO(Sys::Syslog::LOG_DEBUG()), 255, 'LOG_UPTO matches syslog macro');

my ($err, $value) = Sys::Syslog::constant('LOG_AUTH');
ok(!defined $err, 'constant() returns undef error for known macro');
is($value, 32, 'constant() returns known macro value');

like(Sys::Syslog::constant('NOSUCHNAME'), qr/^NOSUCHNAME is not a valid Sys::Syslog macro/,
    'constant() reports invalid macro names');

my $ok = eval {
    Sys::Syslog::openlog_xs('perl', 0, Sys::Syslog::LOG_USER());
    Sys::Syslog::syslog_xs(Sys::Syslog::LOG_INFO(), 'message');
    Sys::Syslog::closelog_xs();
    1;
};
ok($ok, 'native syslog hooks are callable');
is($@, '', 'native syslog hooks do not croak');
