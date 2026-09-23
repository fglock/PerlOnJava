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

my @cases = (
    # Imported re/anyof.t emitted row IDs from the exact A23 map.
    [385,  q{qr/[\x{100}]/},                  'EXACT_REQ8 <\x{100}>'],
    [393,  q{qr/(?i)[\x{100}]/},             'EXACTFU_REQ8 <\x{101}>'],
    [598,  q{qr/(?l)[\x{2029}]/},            'EXACTL <\x{2029}>'],
    [600,  q{qr/(?il)[\x{212A}]/},           'EXACTFL <\x{212a}>'],
    [1297, q{my $a = '(?i)[\x{410}]'; utf8::upgrade($a); qr/$a/},
                                                'EXACTFU_REQ8 <\x{430}>'],
    [1300, q{qr/(?i)[\x{2b9}]/},             'EXACT_REQ8 <\x{2b9}>'],
    [1303, q{qr/(?i)[\x{390}]/},             'EXACTFU_REQ8 <\x{3b9}\x{308}\x{301}>'],
    [1304, q{qr/(?i)[\x{1E9E}]/},            'EXACTFU <ss>'],
    [1305, q{qr/(?iaa)[\x{1E9E}]/},          'EXACTFAA <\x{17f}\x{17f}>'],
    [1306, q{qr/(?i)[\x{FB00}]/},            'EXACTFU <ff>'],
    [1307, q{qr/(?iaa)[\x{FB00}]/},          'EXACT_REQ8 <\x{fb00}>'],
    [1315, q{qr/[a][b]/},                     'EXACT <ab>'],
    [1316, q{qr/[a]\x{100}/},                'EXACT_REQ8 <a\x{100}>'],
    [1318, q{qr/(?i)[b][c]/},                 'EXACTFU <bc>'],
    [1321, q{qr/(?i)b[s]/},                   'EXACTFU <bs>'],
    [1322, q{qr/(?i)b[s]c/},                  'EXACTFU <bsc>'],
    [1323, q{qr/(?i)bs[s]c/},                 'EXACTF <bss>'],
    [1324, q{qr/(?iu)bs[s]c/},                'EXACTFUP <bssc>'],
    [1329, q{qr/(?i)[b]st[s]st/},             'EXACTF <bstsst>'],
    [1330, q{qr/(?iu)[b]st[s]st/},            'EXACTFUP <bstsst>'],
    [1331, q{qr/(?i)[s][s]/},                 'EXACTF <ss>'],
    [1332, q{qr/(?iu)[s][s]/},                'EXACTFUP <ss>'],
);

for my $case (@cases) {
    my ($row, $source, $expected) = @$case;
    is first_program($source), $expected, "anyof row $row exact/fold program";
}

done_testing;
