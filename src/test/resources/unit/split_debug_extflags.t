use strict;
use warnings;
use Test::More tests => 4;

my @cases = (
    [ q{split /\s+/},     qr/^r->extflags:.*\bWHITE\b/ms,      'whitespace regex' ],
    [ q{split /^/},        qr/^r->extflags:.*\bSTART_ONLY\b/ms, 'start anchor' ],
    [ q{split //},         qr/^r->extflags:.*\bNULL\b/ms,       'empty regex' ],
    [ q{use re qw(/x); split qr/ /},
                          qr/^r->extflags:.*\bNULL\b/ms,       'quoted explicit regex under /x' ],
);

for my $case (@cases) {
    my ($source, $expected, $name) = @$case;
    my $output = `$^X -Mre=Debug,COMPILE -e '$source' 2>&1`;
    like($output, $expected, "split debug extflags: $name");
}
