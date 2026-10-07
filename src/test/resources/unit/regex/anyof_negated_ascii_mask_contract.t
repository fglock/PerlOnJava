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

is first_program(q{qr/(?i:[^:])/}), 'NANYOFM[:]',
    'caseless negated punctuation singleton uses the Perl ANYOF mask';
is first_program(q{qr/(?i:[^a])/}), 'NANYOFM[Aa]',
    'caseless negated singleton with fold peers retains both mask bytes';
is first_program(q{qr/(?i:[:])/}), 'EXACT <:>',
    'positive singleton remains an exact node';

done_testing;
