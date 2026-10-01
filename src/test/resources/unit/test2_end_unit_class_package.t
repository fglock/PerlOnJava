use strict;
use warnings;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = <<'PERL';
use Test::More;
use feature 'class';
class PackageProbe;
package main;
ok(1, 'child reaches shutdown');
done_testing;
PERL

my $is_jperl = $^X =~ m{(?:^|/)jperl-exec\z};
my @backends = $is_jperl ? ('JVM', 'interpreter') : ('system Perl');
print '1..', scalar(@backends) * 2, "\n";
my $test = 0;
for my $backend (@backends) {
    my @command = ($^X);
    push @command, '--interpreter' if $backend eq 'interpreter';
    push @command, ('-e', $source);
    my $stderr = gensym();
    my $pid = open3(undef, my $stdout, $stderr, @command);
    my $child_output = do { local $/; <$stdout> // '' };
    my $child_error = do { local $/; <$stderr> // '' };
    waitpid($pid, 0);
    my $status = $? >> 8;

    ++$test;
    print(($status == 0 ? 'ok' : 'not ok'), " $test - $backend child exits successfully\n");
    ++$test;
    my $cleanup_ok = $child_output =~ /ok 1 - child reaches shutdown/
        && $child_output !~ /test2_set_is_end|END failed/
        && $child_error !~ /test2_set_is_end|END failed/;
    print(($cleanup_ok ? 'ok' : 'not ok'), " $test - $backend Test2 END cleanup succeeds\n");
    print "# child stderr: $child_error" if !$cleanup_ok && length $child_error;
}
