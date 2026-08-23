use strict;
use warnings;
use Test::More tests => 4;
use IPC::Cmd qw(run);

{
    local $IPC::Cmd::USE_IPC_RUN = 1;
    my ($ok, $error, $combined, $stdout, $stderr) = run(
        command => [$^X, '-e', 'print "out\n"; warn "err\n"'],
        timeout => 10,
    );
    ok $ok, 'forced IPC::Run executes an argv command';
    is join('', @$stdout), "out\n", 'argv command captures stdout';
    is join('', @$stderr), "err\n", 'argv command captures stderr';
    is $error, undef, 'successful argv command has no IPC::Cmd error';
}
