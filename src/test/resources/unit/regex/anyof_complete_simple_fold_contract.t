use strict;
use warnings;
use Test::More;
use IPC::Open3;
use Symbol qw(gensym);

sub first_program {
    my ($source) = @_;
    my $error = gensym;
    my $pid = open3(my $input, my $output, $error,
        $^X, '-e', "use strict; use warnings; use re qw(Debug COMPILE); $source");
    close $input;
    local $/;
    my $stdout = <$output> // '';
    my $stderr = <$error> // '';
    waitpid $pid, 0;
    die "debug child failed ($?): $stdout$stderr" if $?;
    my @lines = split /\n/, $stderr;
    shift @lines while @lines && $lines[0] !~ /Final program/;
    shift @lines;
    my $line = shift(@lines) // die "missing Final program: $stderr";
    $line =~ s/\s*\(\d+\)\s*//;
    $line =~ s/^\s*\d+:\s*//;
    return $line;
}

my @latin1_lower = qw(e0 e1 e2 e3 e4 e6 e7 e8 e9 ea eb ec ee ef
                      f0 f1 f2 f3 f4 f5 f6 f8 f9 fa fb fc fd fe);
for my $lower (@latin1_lower) {
    my $upper = sprintf '%x', hex($lower) - 0x20;
    my $source = sprintf 'qr/[\\x{%s}\\x{%s}]/', $lower, $upper;
    my $expected = sprintf 'EXACTFU <\\x{%s}>', $lower;
    is first_program($source), $expected,
        "complete Latin-1 fold class U+$upper/U+$lower";
}

is first_program(q{qr/[\x{103}\x{102}]/}),
    'EXACTFU_REQ8 <\x{103}>', 'complete U+0102/U+0103 fold class';

my @u2029 = (
    [q{qr/(?i)[\x{2029}]/},   'EXACT_REQ8 <\x{2029}>', '/i'],
    [q{qr/(?iu)[\x{2029}]/},  'EXACT_REQ8 <\x{2029}>', '/iu'],
    [q{qr/(?il)[\x{2029}]/},  'EXACTL <\x{2029}>',     '/il'],
    [q{qr/(?iaa)[\x{2029}]/}, 'EXACT_REQ8 <\x{2029}>', '/iaa'],
);
for my $case (@u2029) {
    is first_program($case->[0]), $case->[1], "U+2029 $case->[2] mode";
}

done_testing;
