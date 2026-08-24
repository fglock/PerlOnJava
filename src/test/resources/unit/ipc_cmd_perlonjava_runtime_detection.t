use strict;
use warnings;
use Test::More tests => 6;
use IPC::Cmd qw(run);

SKIP: {
    skip 'PerlOnJava runtime identity helpers are not installed', 2
        unless IPC::Cmd->can('_is_perlonjava_runtime');
    ok IPC::Cmd::_is_perlonjava_runtime(),
        'IPC::Cmd recognizes the active jperl launcher';
    require PerlOnJava::Process;
    ok PerlOnJava::Process::_is_perlonjava_runtime(),
        'PerlOnJava::Process recognizes the active jperl launcher';
}

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
