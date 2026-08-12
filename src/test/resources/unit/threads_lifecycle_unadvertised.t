use strict;
use warnings;
use threads;
use Config;

print "1..8\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $exited = threads->create(sub { threads->exit(17); return 99 });
check(!defined $exited->join, 'threads exit returns undef from join');
check(!($exited->error || ''), 'threads exit is not an error');

my $failed = threads->create(sub { die "child boom\n" });
$failed->join;
check($failed->error =~ /child boom/, 'uncaught child error is retained');
check(!$failed->is_joinable, 'failed joined thread is cleaned up');

my $nested = threads->create(sub {
    my $inner = threads->create(sub { return threads->self->tid });
    return ($inner->tid, $inner->join);
});
my $nested_value = $nested->join;
check($nested_value > $nested->tid, 'nested child receives a later tid');

my $detached = threads->create(sub { return 1 });
$detached->detach;
check($detached->is_detached, 'detached state remains observable');
check($Config{archname} =~ /^java-/ ? !defined $detached->kill('KILL') : 1,
    'kill limitation is explicit on PerlOnJava');
check($nested_value > 0, 'nested child completed before main shutdown');
