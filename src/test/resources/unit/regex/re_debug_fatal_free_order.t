use strict;
use warnings;
use Test::More;
use IPC::Open3;
use Symbol qw(gensym);

my $skip_launcher = $^X eq 'jperl'
    && !-f 'target/perlonjava-5.44.0.jar';

sub run_child {
    my ($code) = @_;
    my $launcher = $^X;
    if ($launcher eq 'jperl') {
        $launcher = $^O eq 'MSWin32' ? 'jperl.bat' : './jperl';
    }

    my $error = gensym;
    my $pid = open3(my $input, my $output, $error,
        $launcher, '-e', $code);
    close $input;
    local $/;
    my $stdout = <$output> // '';
    my $stderr = <$error> // '';
    close $output;
    close $error;
    waitpid $pid, 0;
    return ($stdout, $stderr, $?);
}

SKIP: {
    skip 'nested jperl launcher requires built target jar', 10
        if $skip_launcher;

    my (undef, $fatal, $fatal_status) = run_child(
        q!use re Debug=>"ALL"; qr{(?{a})(?<b>\g{c}}!);
    isnt $fatal_status, 0, 'top-level malformed regex is fatal';
    my $diagnostic = index $fatal, 'Unmatched ( in regex';
    my $fatal_free = index $fatal,
        'Freeing REx: "(?{a})(?<b>\g{c}"';
    ok $diagnostic >= 0, 'fatal diagnostic is published';
    ok $fatal_free >= 0, 'failed regex lifecycle is freed';
    ok $diagnostic < $fatal_free,
        'top-level fatal diagnostic precedes the failed-regex free';
    is(() = $fatal =~ /Freeing REx: /g, 1,
        'top-level failed regex is freed exactly once');

    my (undef, $caught, $caught_status) = run_child(
        q!use re Debug=>"PARSE"; eval q{qr{(?<b>\g{c}}}; print STDERR "CAUGHT:$@"!);
    is $caught_status, 0, 'caught eval compile failure remains nonfatal';
    my $eval_free = index $caught, 'Freeing REx: "(?<b>\g{c}"';
    my $caught_diagnostic = index $caught, 'CAUGHT:Unmatched ( in regex';
    ok $eval_free >= 0 && $eval_free < $caught_diagnostic,
        'caught eval keeps its immediate failed-regex lifecycle';

    my (undef, $success, $success_status) = run_child(
        q!use re Debug=>"PARSE"; qr{phase36_a69_success}!);
    is $success_status, 0, 'successful debug regex remains successful';
    like $success, qr/Freeing REx: "phase36_a69_success"/,
        'successful regex keeps its shutdown free lifecycle';
    unlike $success, qr/Unmatched \( in regex/,
        'successful lifecycle has no fatal diagnostic';
}

done_testing;
