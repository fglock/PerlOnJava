use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = <<'PERL';
sub TIEHANDLE { bless {} }
sub PRINT { next }
tie *STDERR, '';
{ map ++$_, 1 }
PERL
my $sort_source = '@items = sort { last; } (1, 2);';

sub run_child {
    my (@command) = @_;
    my $stderr = gensym();
    my $pid = open3(undef, my $stdout, $stderr, @command);
    local $/;
    my $out = <$stdout> // '';
    my $err = <$stderr> // '';
    waitpid($pid, 0);
    return ($? >> 8, $out . $err);
}

my ($perl_status, $perl_output) = run_child(
    'timeout', '60', 'perl', '-e', $source,
);
isnt($perl_status, 0, 'system Perl rejects loop control in an unused named sub');
like($perl_output, qr/Can't "next" outside a loop block/,
    'system Perl reports the subroutine loop-control diagnostic');
my ($sort_perl_status, $sort_perl_output) = run_child(
    'timeout', '60', 'perl', '-e', $sort_source,
);
isnt($sort_perl_status, 0, 'system Perl rejects loop control in a sort comparator');
like($sort_perl_output, qr/Can't "last" outside a loop block/,
    'system Perl reports the sort comparator loop-control diagnostic');

if ($^X =~ m{(?:^|[\\/])jperl(?:\.bat|(?:-exec)?)\z} || $^X eq 'jperl') {
    my $launcher = $^X eq 'jperl' || $^X =~ m{/jperl-exec\z} ? './jperl' : $^X;
    my ($jvm_status, $jvm_output) = run_child(
        'timeout', '60', $launcher, '-e', $source,
    );
    isnt($jvm_status, 0, 'JVM backend rejects loop control in an unused named sub');
    like($jvm_output, qr/Can't "next" outside a loop block/,
        'JVM backend reports the subroutine loop-control diagnostic');
    my ($sort_jvm_status, $sort_jvm_output) = run_child(
        'timeout', '60', $launcher, '-e', $sort_source,
    );
    isnt($sort_jvm_status, 0, 'JVM backend rejects loop control in a sort comparator');
    like($sort_jvm_output, qr/Can't "last" outside a loop block/,
        'JVM backend reports the sort comparator loop-control diagnostic');

    my ($interpreter_status, $interpreter_output) = run_child(
        'timeout', '60', $launcher, '--interpreter', '-e', $source,
    );
    isnt($interpreter_status, 0, 'interpreter backend rejects loop control in an unused named sub');
    like($interpreter_output, qr/Can't "next" outside a loop block/,
        'interpreter backend reports the subroutine loop-control diagnostic');
    my ($sort_interpreter_status, $sort_interpreter_output) = run_child(
        'timeout', '60', $launcher, '--interpreter', '-e', $sort_source,
    );
    isnt($sort_interpreter_status, 0, 'interpreter backend rejects loop control in a sort comparator');
    like($sort_interpreter_output, qr/Can't "last" outside a loop block/,
        'interpreter backend reports the sort comparator loop-control diagnostic');
    done_testing(12);
} else {
    done_testing(4);
}
