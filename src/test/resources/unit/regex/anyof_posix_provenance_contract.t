use strict;
use warnings;
use Test::More;
use IPC::Open3;

sub first_program {
    my ($source) = @_;
    # re Debug => COMPILE can fill Windows' smaller stderr pipe before the
    # child closes stdout.  Merge both streams so the parent always drains the
    # complete debug transcript and cannot deadlock on the second pipe.
    my $pid = open3(my $input, my $output, undef,
        $^X, '-e', "use strict; use warnings; use re qw(Debug COMPILE); $source");
    close $input;
    local $/;
    my $transcript = <$output> // '';
    waitpid $pid, 0;
    die "debug child failed ($?): $transcript" if $?;
    my @lines = split /\n/, $transcript;
    shift @lines while @lines && $lines[0] !~ /Final program/;
    shift @lines;
    my $line = shift(@lines) // die "missing Final program: $transcript";
    $line =~ s/\s*\(\d+\)\s*//;
    $line =~ s/^\s*\d+:\s*//;
    return $line;
}

my @cases = (
    # Imported re/anyof.t emitted row IDs from the exact A23 map.
    [586, q{qr/[[:cntrl:]]/},                 'POSIXD[:cntrl:]'],
    [608, q{qr/[[:alpha:]]/},                 'POSIXD[:alpha:]'],
    [610, q{qr/[[:^alpha:]\x{2C2}]/},         'NPOSIXU[:alpha:]'],
    [611, q{qr/(?l)[[:alpha:]]/},             'POSIXL[:alpha:]'],
    [614, q{qr/(?u)[[:alpha:]]/},             'POSIXU[:alpha:]'],
    [616, q{qr/(?a)[[:alpha:]]/},             'POSIXA[:alpha:]'],
    [694, q{qr/[[:digit:]]/},                 'POSIXU[\d]'],
    [827, q{qr/[\v]/},                        'POSIXU[\v]'],
    [830, q{qr/(?l)[\v]/},                    'POSIXU[\v]'],
    [831, q{qr/(?l)[^\v]/},                   'NPOSIXU[\v]'],
    [832, q{qr/(?l)[\V\x{2C2}]/},             'NPOSIXU[\v]'],
    [835, q{qr/(?a)[\v]/},                    'POSIXU[\v]'],
    [836, q{qr/(?a)[^\v]/},                   'NPOSIXU[\v]'],
    [837, q{qr/(?a)[\V\x{2C2}]/},             'NPOSIXU[\v]'],
    [884, q{qr/(?i)[[:lower:]]/},             'POSIXD[:cased:]'],
    [892, q{qr/(?i)(?a)[[:lower:]]/},         'POSIXA[:alpha:]'],
    [906, q{qr/(?i)[\d\w]/},                 'POSIXD[\w]'],
    [908, q{qr/(?i)(?l)[\D\w]/},             'ANYOFPOSIXL{i}[\w\D][0100-INFTY]'],
    [914, q{qr/(?l:[\s\x{212A}])/},          'ANYOFPOSIXL[\s][1680 2000-200A 2028-2029 202F 205F 212A 3000]'],
    [915, q{qr/(?l:[^\S\x{202F}])/},         'ANYOFPOSIXL[^\S][1680 2000-200A 2028-2029 205F 3000]'],
);

for my $case (@cases) {
    my ($row, $source, $expected) = @$case;
    is first_program($source), $expected, "anyof row $row POSIX provenance";
}

done_testing;
