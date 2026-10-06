use strict;
use warnings;
use Test::More tests => 6;

my $close = \&CORE::close;
my $closedir = \&CORE::closedir;
my $opendir = \&CORE::opendir;
my $readdir = \&CORE::readdir;

no warnings 'unopened';
is($close->('no_such_handle'), '',
    'CORE::close code reference returns false for an unopened handle');
is_deeply([$close->('no_such_handle')], [''],
    'CORE::close code reference preserves its false list result');

my $dh;
ok($opendir->($dh, '.'), 'CORE::opendir code reference opens a directory');
ok(scalar $readdir->($dh), 'CORE::readdir code reference reads an entry');
ok(scalar(() = $readdir->($dh)) >= 0,
    'CORE::readdir code reference supports list context');
ok($closedir->($dh), 'CORE::closedir code reference closes a directory');
