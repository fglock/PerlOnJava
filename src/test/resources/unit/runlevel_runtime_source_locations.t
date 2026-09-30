use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempfile);

my ($source, $source_path) = tempfile();
print {$source} qq{#line 30 "logical-source.pl"\ndie "source failure";\n}
    or die "write temporary source: $!";
close $source or die "close temporary source: $!";
eval { require $source_path };
my $eval_error = $@;
unlink $source_path or die "unlink temporary source: $!";
like(
    $eval_error,
    qr/source failure at logical-source\.pl line 30\./,
    'runtime diagnostics retain #line coordinates',
);

{
    package Runlevel::TiedStderr;
    sub TIEHANDLE { bless {}, $_[0] }
    sub PRINT { $main::captured_warning .= $_[1] }
}

our $captured_warning = '';
tie *STDERR, 'Runlevel::TiedStderr';
my $expected_warning_line = __LINE__ + 3;
{
    use warnings 'uninitialized';
    print undef;
}
untie *STDERR;
like(
    $captured_warning,
    qr/Use of uninitialized value in print at \Q$0\E line \Q$expected_warning_line\E\./,
    'print warning preserves the print call site through tied STDERR',
);

my $control_error;
eval qq{#line 41 "logical-control.pl"\nsub bad_next { next; }\nbad_next();\n};
$control_error = $@;
like(
    $control_error,
    qr/Can't "next" outside a loop block at logical-control\.pl line 41\.?/,
    'non-local loop-control diagnostics retain their source token location',
);

{
    package Runlevel::TiedStderrNext;
    sub TIEHANDLE { bless {}, $_[0] }
    sub PRINT { next }
}
my $tied_next_error;
{
    tie *STDERR, 'Runlevel::TiedStderrNext';
    eval { print STDERR "trigger tied PRINT\n" };
    $tied_next_error = $@;
    untie *STDERR;
}
like(
    $tied_next_error,
    qr/Can't "next" outside a loop block/,
    'loop control from tied STDERR PRINT escapes as a diagnostic',
);

done_testing;
