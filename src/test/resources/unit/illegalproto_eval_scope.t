use strict;
use warnings;
use Test::More;

# Prototype diagnostics are compile-time warnings. They follow the lexical
# warning state of the code being compiled, which for an eval STRING is the
# eval body, not the code that happens to be running.
sub capture_eval {
    my ($code) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, $_[0] };
    my $ok = eval $code;
    return ($ok, $@, @warnings);
}

{
    my ($ok, $err, @warnings) = capture_eval(
        'use warnings; sub eval_after_at ($@x) { 1 } 1');
    ok $ok, 'eval with a malformed prototype compiles when warnings are not fatal';
    is scalar(@warnings), 2, 'eval reports both prototype diagnostics';
    like $warnings[0], qr/^Prototype after '\@' for main::eval_after_at : \$\@x/,
        'the after-@ diagnostic comes first';
    like $warnings[1], qr/^Illegal character in prototype for main::eval_after_at : \$\@x/,
        'the illegal-character diagnostic follows';
}

{
    my ($ok, $err, @warnings) = capture_eval(
        'no warnings; sub eval_silent ($@x) { 1 } 1');
    ok $ok, 'eval under no warnings compiles';
    is scalar(@warnings), 0, 'no warnings in the eval silences prototype diagnostics';
}

{
    my ($ok, $err, @warnings) = capture_eval(
        'use warnings FATAL => "illegalproto"; sub eval_fatal ($@x) { 1 } 1');
    ok !$ok, 'FATAL illegalproto in the eval makes the diagnostic fatal';
    like $err, qr/^Prototype after '\@' for main::eval_fatal : \$\@x/,
        'the fatal error is the first prototype diagnostic';
    is scalar(@warnings), 0, 'a fatal diagnostic is not also reported as a warning';
}

{
    use warnings FATAL => 'all';
    my ($ok, $err, @warnings) = capture_eval(
        'use warnings NONFATAL => "all"; sub eval_nonfatal ($@x) { 1 } 1');
    ok $ok, 'an inner NONFATAL overrides an outer FATAL for the eval body';
    is scalar(@warnings), 2, 'the eval body reports its prototype diagnostics as warnings';
}

{
    my ($ok, $err, @warnings) = capture_eval(
        'use warnings; sub eval_only_illegal (x) { 1 } 1');
    ok $ok, 'a single illegal prototype character compiles in an eval';
    is scalar(@warnings), 1, 'a single illegal character reports one diagnostic';
    like $warnings[0], qr/^Illegal character in prototype for main::eval_only_illegal : x/,
        'the diagnostic names the illegal character';
}

done_testing;
