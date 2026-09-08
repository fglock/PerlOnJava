#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use IPC::Open3;
use IO::Select;
use Symbol qw(gensym);

# Regression for #1263: ProcessInputHandle drains the Java stream in a reader
# thread.  eof must inspect that synchronized buffer, not peek the Java stream,
# so callers such as IPC::Open3::Utils see queued stdout/stderr before EOF.
my ($stdin, $stdout, $stderr) = (undef, undef, gensym);
my @command = $^O eq 'MSWin32'
    ? ('cmd.exe', '/v:on', '/c', 'set /p line=& echo out:!line!& echo err:!line! 1>&2')
    : ('sh', '-c', 'read line; printf "out:%s\\n" "$line"; printf "err:%s\\n" "$line" >&2');
my $pid = open3(
    $stdin, $stdout, $stderr, @command
);

print {$stdin} "payload\n";
ok(close($stdin), 'closing stdin lets the child complete');

# Give the asynchronous readers time to transfer the child output into their
# private buffers.  The process has exited, but buffered data is still readable.
select undef, undef, undef, 0.05;
ok(!eof($stdout), 'stdout with buffered child data is not EOF');
ok(!eof($stderr), 'stderr with buffered child data is not EOF');

my $selector = IO::Select->new($stdout, $stderr);
my (%captured, $handler_calls, $short_circuit) = ((), 0, 0);

READ_LOOP:
while (my @ready = $selector->can_read(2)) {
    for my $fh (@ready) {
        if (eof($fh)) {
            $selector->remove($fh);
            close($fh);
            next;
        }

        while (my $line = <$fh>) {
            $handler_calls++;
            $captured{fileno($fh)} .= $line;
            $short_circuit = 1 if $line =~ /\Aout:payload\r?\n\z/;
            last READ_LOOP if $short_circuit;
        }
    }
}

ok($handler_calls, 'buffered data invokes the read handler');
ok($short_circuit, 'handler can short-circuit after stdout is read');
like($captured{fileno($stdout)} // '', qr/\Aout:payload\r?\n\z/,
    'stdout buffered data is preserved');

# Finish draining stderr after the short-circuit path, mirroring the lifecycle
# cleanup in IPC::Open3::Utils.
my $stderr_text = '';
while (!eof($stderr)) {
    my $line = <$stderr>;
    $stderr_text .= $line if defined $line;
}
like($stderr_text, qr/\Aerr:payload\r?\n\z/,
    'stderr buffered data is preserved');

close($stdout);
close($stderr);
is(waitpid($pid, 0), $pid, 'child is reaped after both pipes close');
is($? >> 8, 0, 'child exits successfully');

done_testing;
