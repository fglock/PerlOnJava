use strict;
use warnings;
use Test::More;
use IPC::Open3;
use IO::Select;
use Symbol qw(gensym);

my @built_jars = glob 'target/perlonjava-*.jar';
my $skip_launcher = $^X eq 'jperl'
    && (!-x './jperl' || !@built_jars);

sub trace_region {
    my ($length) = @_;
    my $launcher = $^X;
    if ($launcher eq 'jperl') {
        $launcher = $^O eq 'MSWin32' ? 'jperl.bat' : './jperl';
    }
    my $body = qq{
        use threads;
        use re 'debug';
        sub traced_split {
            warn "===\n";
            split /[.;]+['"]+/, \$_[0];
            warn "\n===\n";
        }
        my \$buffer = '.' x $length;
        traced_split(\$buffer);
    };

    my $error = gensym;
    my $pid = open3(my $input, my $output, $error,
        $launcher, '-e', $body);
    close $input;
    my $selector = IO::Select->new($output, $error);
    my ($stdout, $stderr) = ('', '');
    my %target = (
        fileno($output) => \$stdout,
        fileno($error) => \$stderr,
    );
    my $read_error;
    {
        local $SIG{ALRM} = sub { die "nested launcher timed out\n" };
        eval {
            alarm 30;
            while (my @ready = $selector->can_read) {
                for my $handle (@ready) {
                    my $chunk = '';
                    my $bytes = sysread $handle, $chunk, 8192;
                    if (!defined($bytes) || $bytes == 0) {
                        $selector->remove($handle);
                        close $handle;
                        next;
                    }
                    ${$target{fileno($handle)}} .= $chunk;
                }
            }
            alarm 0;
            1;
        } or $read_error = $@ || 'nested launcher read failed';
        alarm 0;
    }
    if ($read_error) {
        # jperl replaces its launcher process with the JVM, so terminating this
        # exact child also terminates all of its Java regex worker threads.
        kill 9, $pid;
        waitpid $pid, 0;
        die $read_error;
    }
    waitpid $pid, 0;
    my $status = $?;
    die "debug child failed ($status): $stdout$stderr" if $status;
    my (undef, $region, undef) = split /\n===\n/, $stderr;
    return $region // '';
}

SKIP: {
    skip 'nested jperl launcher requires built target jar', 4
        if $skip_launcher;

    my $region10 = trace_region(10);
    my $region100 = trace_region(100);
    my $lines10 = scalar split /\n/, $region10;
    my $lines100 = scalar split /\n/, $region100;

    unlike $region10, qr/Compiling REx|Final program:/,
        'literal compilation is reported before the runtime trace region';
    like $region10, qr/Matching REx/,
        'the runtime region contains execution tracing';
    ok $lines100 > $lines10,
        'the execution trace grows with the subject';
    ok abs(($lines100 / 100) - 3) < 0.2,
        'unthreaded execution tracing remains linear';
}

done_testing;
