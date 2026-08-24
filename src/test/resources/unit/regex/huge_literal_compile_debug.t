use strict;
use warnings;
use Test::More tests => 2;
use IPC::Open3;

sub exact_program_labels {
    my ($pattern) = @_;
    my $source = "use utf8; use re qw(Debug COMPILE); qr/$pattern/;";

    # Feed the source over stdin: the exact pat.t-sized fixture is larger than
    # common command-line argument limits.  Merge stderr into the drained
    # output pipe so compile debug cannot deadlock a child on a full pipe.
    my $pid = open3(my $input, my $output, undef, $^X, '-');
    binmode $input, ':encoding(UTF-8)';
    print {$input} $source;
    close $input;
    local $/;
    my $transcript = <$output> // '';
    waitpid $pid, 0;
    die "debug child failed ($?): $transcript" if $?;

    my @labels;
    my $in_final_program = 0;
    for my $line (split /\n/, $transcript) {
        if ($line =~ /Final program:/) {
            $in_final_program = 1;
            next;
        }
        next unless $in_final_program;
        if ($line =~ /^\s*(?:\d+:\s*)?
                (LEXACT_REQ8|LEXACT|EXACT|END)\b/x) {
            push @labels, $1;
            last if $1 eq 'END';
        }
    }
    die "missing complete exact program: $transcript"
        unless @labels && $labels[-1] eq 'END';
    return join ',', @labels;
}

my $literal = ("0123456789" x 26214) x 2;
is exact_program_labels($literal), 'LEXACT,LEXACT,EXACT,END',
    'pat assertion 831 exposes ordered huge byte exact labels';

substr($literal, 260000, 1) = "\x{100}";
is exact_program_labels($literal), 'LEXACT_REQ8,LEXACT,EXACT,END',
    'pat assertion 834 exposes ordered huge wide exact labels';
