#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 14;

use Sys::Syslog qw(:DEFAULT setlogsock LOG_INFO LOG_ERR LOG_LOCAL7 LOG_MASK LOG_UPTO);

is(LOG_INFO(), 6, 'LOG_INFO exports from Sys::Syslog');
is(LOG_LOCAL7(), 184, 'LOG_LOCAL7 exports from Sys::Syslog');

openlog('perl-test', 'pid', 'local7');
is($Sys::Syslog::facility, 'local7', 'openlog records current facility');

ok(syslog('info', 'hello %s', 'syslog'), 'syslog accepts a string priority');
ok(syslog(LOG_INFO() | LOG_LOCAL7(), 'hello numeric'), 'syslog accepts a numeric priority');

my $oldmask = setlogmask(LOG_MASK(LOG_ERR()));
ok(defined $oldmask, 'setlogmask returns previous mask');
is(setlogmask(0), LOG_MASK(LOG_ERR()), 'setlogmask(0) leaves mask unchanged');
setlogmask($oldmask);

ok(setlogsock('native'), 'setlogsock is available');
ok(eval 'use Sys::Syslog qw(:DEFAULT setlogsock); 1', 'Log::Any import form works');
is($@, '', 'Log::Any import form has no error');

closelog();
is($Sys::Syslog::facility, '', 'closelog clears current facility');

ok(eval { Sys::Syslog::LOG_USER(); 1 }, 'autoloaded constant is callable');
is(Sys::Syslog::LOG_USER(), 8, 'autoloaded constant has expected value');
like(eval { Sys::Syslog::NO_SUCH_SYSLOG_CONSTANT(); 1 } ? '' : $@,
    qr/NO_SUCH_SYSLOG_CONSTANT is not a valid Sys::Syslog macro/,
    'invalid autoloaded constant croaks');
