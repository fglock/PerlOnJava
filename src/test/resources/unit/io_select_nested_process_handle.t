use strict;
use warnings;
use Test::More tests => 3;
use IPC::Open3;
use IO::Select;

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $selector = IO::Select->new;

sub add_delayed_child {
    my ($selector, $launcher) = @_;
    my $pid = open3(my $input, my $output, undef, $launcher, '-e',
        'select undef, undef, undef, 0.2; print qq(ready\n)');
    close $input;

    # TAP::Parser::Multiplexer stores this same nested aggregate shape in
    # IO::Select. The nested handle must outlive this subroutine's lexicals.
    $selector->add([$output]);
    return $pid;
}

my $pid = add_delayed_child($selector, $launcher);
# On Windows CI the nested PerlOnJava child can take several seconds to start
# under parallel load. Keep the timeout long enough to test handle lifetime
# instead of measuring JVM startup latency.
my @ready = $selector->can_read(30);
is(scalar @ready, 1, 'nested process handle remains selectable after scope exit');

my $line = @ready ? readline($ready[0][0]) : undef;
is($line, "ready\n", 'selected nested process handle remains readable');

waitpid $pid, 0;
is($?, 0, 'delayed child exited successfully');
