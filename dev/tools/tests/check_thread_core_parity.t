use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $checker = File::Spec->catfile(
    $root, 'dev', 'tools', 'check_thread_core_parity.pl');
my $temporary = tempdir(CLEANUP => 1);
my $prefix = 'sample';

my %pairs = (
    pat_re_eval         => 'pat_re_eval_thr',
    pat_rt_report       => 'pat_rt_report_thr',
    pat_special_cc      => 'pat_special_cc_thr',
    reg_email           => 'reg_email_thr',
    regexp_unicode_prop => 'regexp_unicode_prop_thr',
    speed               => 'speed_thr',
    pat                 => 'pat_thr',
    pat_advanced        => 'pat_advanced_thr',
    pat_psycho          => 'pat_psycho_thr',
    regexp_qr_embed     => 'regexp_qr_embed_thr',
);

my (%direct, %wrapper);
for my $name (keys %pairs) {
    $direct{"perl5_t/t/re/$name.t"} = passing_result();
    $wrapper{"perl5_t/t/re/$pairs{$name}.t"} = passing_result();
}
$wrapper{'perl5_t/t/re/stclass_threads.t'} = passing_result();
$wrapper{'perl5_t/t/re/user_prop_race_thr.t'} = passing_result();

write_report("$temporary/$prefix-direct.json", \%direct);
write_report("$temporary/$prefix-wrappers.json", \%wrapper);
my ($pass_status, $pass_output) = run_checker();
is($pass_status, 0, 'equal direct and wrapper reports pass')
    or diag($pass_output);
like($pass_output, qr/10 direct\/wrapper pairs, 2 standalone wrappers/,
    'success reports the complete matrix');

$wrapper{'perl5_t/t/re/speed_thr.t'}{ok_count} = 0;
$wrapper{'perl5_t/t/re/speed_thr.t'}{not_ok_count} = 1;
write_report("$temporary/$prefix-wrappers.json", \%wrapper);
my ($fail_status, $fail_output) = run_checker();
isnt($fail_status, 0, 'a wrapper regression fails');
like($fail_output, qr/speed_thr\.t: ok_count is 0; expected at least 1/,
    'failure identifies the regressed wrapper and metric');

done_testing;

sub passing_result {
    return {
        status => 'pass',
        ok_count => 1,
        not_ok_count => 0,
        actual_tests_run => 1,
        planned_tests => 1,
        incomplete_tests => 0,
    };
}

sub write_report {
    my ($path, $results) = @_;
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} JSON::PP->new->utf8->canonical->encode({results => $results});
    close $fh or die "cannot close $path: $!";
}

sub run_checker {
    my $pid = open my $command, '-|';
    die "cannot fork parity checker: $!" unless defined $pid;
    if ($pid == 0) {
        open STDERR, '>&', \*STDOUT or die "cannot redirect stderr: $!";
        exec $^X, $checker, $temporary, $prefix;
        die "cannot execute parity checker: $!";
    }
    my $output = do { local $/; <$command> };
    close $command;
    return ($? >> 8, $output // '');
}
