use strict;
use warnings;
use Test::More tests => 3;

my @exit_command = ($^X, '-e', 'exit 7');
my $status = system { $exit_command[0] } @exit_command;
is($status >> 8, 7, 'indirect system does not pass argv0 as an extra argument');

my @zero_command = ($^X, '-e', 'exit 0');
$status = system { $zero_command[0] } @zero_command;
is($status, 0, 'indirect system preserves list expansion after argv0');

if ($^O eq 'MSWin32') {
    my $shell = $ENV{COMSPEC} || $ENV{ComSpec} || 'cmd.exe';
    exec { $shell } (
        $shell,
        '/d', '/s', '/c',
        'echo ok 3 - indirect exec does not pass argv0 as an extra argument',
    );
}

exec { '/bin/sh' } (
    'ignored-argv0',
    '-c',
    q{printf '%s\n' 'ok 3 - indirect exec does not pass argv0 as an extra argument'},
);
