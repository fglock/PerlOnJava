use strict;
use warnings;
use Test::More;

use DBI qw(
    SQL_ARRAY_LOCATOR SQL_MULTISET_LOCATOR
    SQL_INTERVAL_YEAR SQL_INTERVAL_MINUTE_TO_SECOND
);
is SQL_ARRAY_LOCATOR, 51, 'DBI exports SQL_ARRAY_LOCATOR';
is SQL_MULTISET_LOCATOR, 56, 'DBI exports SQL_MULTISET_LOCATOR';
is SQL_INTERVAL_YEAR, 101, 'DBI exports SQL_INTERVAL_YEAR';
is SQL_INTERVAL_MINUTE_TO_SECOND, 113,
    'DBI exports SQL_INTERVAL_MINUTE_TO_SECOND';

use PadWalker qw(peek_my peek_our);

sub inspect_lexicals {
    my $scalar = 'scalar value';
    my @array = qw(one two);
    my %hash = (answer => 42);
    my $pad = peek_my(0);
    is ${$pad->{'$scalar'}}, 'scalar value', 'peek_my exposes a live scalar';
    is_deeply $pad->{'@array'}, [qw(one two)], 'peek_my exposes a live array';
    is_deeply $pad->{'%hash'}, {answer => 42}, 'peek_my exposes a live hash';
    ${$pad->{'$scalar'}} = 'changed';
    is $scalar, 'changed', 'peek_my returns an alias to the pad cell';
}
inspect_lexicals();

our $package_value = 7;
sub inspect_package_variables {
    our $package_value;
    my $pad = peek_our(0);
    is ${$pad->{'$package_value'}}, 7, 'peek_our exposes an our scalar';
}
inspect_package_variables();

my (@parts, $fallback);
(@parts ? $parts[-1] : defined $fallback ? $fallback : ($parts[0] = "''")) .= "\n";
is $parts[0], "''\n", 'parenthesized ternary branch remains a scalar lvalue';

sub localize_readonly_argument {
    local $_[0] = 'localized';
    return $_[0];
}
is localize_readonly_argument('literal'), 'localized',
    'localizing a read-only argument installs a writable array slot';

sub assign_readonly_argument_to_itself {
    $_[0] = $_[0];
    return $_[0];
}
is assign_readonly_argument_to_itself('literal'), 'literal',
    'assigning a read-only literal argument to itself is a no-op';
is assign_readonly_argument_to_itself(42), 42,
    'numeric read-only argument identity assignment is a no-op';

sub assign_equal_copy_to_readonly_argument {
    my $copy = $_[0];
    $_[0] = $copy;
}
eval { assign_equal_copy_to_readonly_argument('literal') };
like $@, qr/Modification of a read-only value attempted/,
    'an equal value in another scalar does not make a read-only argument writable';

my $unicode_diagnostic = 'Code point \\u0000 is not valid';
my $runtime_pattern = '\\u0000';
ok $unicode_diagnostic =~ $runtime_pattern,
    'runtime regex treats unrecognized \\u as a literal u';

require IO::File;
{
    my $ascii_file = IO::File->new_tmpfile;
    my $encoding_warning = '';
    local $SIG{__WARN__} = sub { $encoding_warning .= $_[0] };
    binmode $ascii_file, ':encoding(us-ascii)';
    print {$ascii_file} "\x{A3}";
    $ascii_file->flush;
    like $encoding_warning, qr/does not map to ascii/,
        'encoding layer warns when output is not representable';
}

use IPC::Cmd qw(run);
{
    local $IPC::Cmd::USE_IPC_RUN = 1;
    my ($ok, $error, $combined, $stdout, $stderr) = run(
        command => [$^X, '-e', 'print "out\\n"; warn "err\\n"'],
        timeout => 10,
    );
    ok $ok, 'IPC::Cmd runs argv commands when IPC::Run is forced';
    is join('', @$stdout), "out\n", 'IPC::Cmd captures stdout separately';
    is join('', @$stderr), "err\n", 'IPC::Cmd captures stderr separately';
    is $error, undef, 'successful command has no IPC::Cmd error';
}

use IO::Poll qw(POLLIN POLLOUT);
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
{
    pipe my $pipe_read, my $pipe_write or die "pipe: $!";
    socketpair my $socket_read, my $socket_write,
        AF_UNIX, SOCK_STREAM, PF_UNSPEC or die "socketpair: $!";
    syswrite $pipe_write, 'x' or die "syswrite: $!";

    my ($pipe_fd, $pipe_events) = (fileno($pipe_read), POLLIN);
    my ($socket_fd, $socket_events) = (fileno($socket_write), POLLOUT);
    my $ready = IO::Poll::_poll(
        0, $pipe_fd, $pipe_events, $socket_fd, $socket_events);
    cmp_ok $ready, '>=', 2,
        'poll reports ready non-socket and socket descriptors together';
    ok $pipe_events & POLLIN, 'poll preserves the ready pipe event';
    ok $socket_events & POLLOUT, 'poll includes the ready socket event';
    my $zero_written = syswrite $socket_write, '', 0;
    is $zero_written, 0, 'zero-length socket syswrite succeeds with zero';
}

done_testing;
