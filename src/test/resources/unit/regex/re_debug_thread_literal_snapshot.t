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
    my ($threaded) = @_;
    my $launcher = $^X;
    if ($launcher eq 'jperl') {
        $launcher = $^O eq 'MSWin32' ? 'jperl.bat' : './jperl';
    }
    my $invocation = $threaded
        ? q{my $thread = threads->create('traced_split', $buffer); $thread->join();}
        : q{traced_split($buffer);};
    my $body = qq{
        use threads;
        use re 'debug';
        sub traced_split {
            warn "\n===\n";
            split /[.;]+['"]+/, \$_[0];
            warn "\n===\n";
        }
        my \$buffer = '.' x 10;
        $invocation
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
        kill 9, $pid;
        waitpid $pid, 0;
        die $read_error;
    }
    waitpid $pid, 0;
    die "debug child failed ($?): $stdout$stderr" if $?;
    my (undef, $region, undef) = split /\n===\n/, $stderr;
    return $region // '';
}

SKIP: {
    skip 'nested jperl launcher requires built target jar', 1
        if $skip_launcher;

    my $threaded = trace_region(1);

    unlike $threaded, qr/Compiling REx|Final program:/,
        'child execution does not re-report inherited literal compilation';
}

done_testing;
