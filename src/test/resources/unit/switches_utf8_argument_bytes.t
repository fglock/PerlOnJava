use strict;
use warnings;
use Test::More;

my $program = q{printf q(%vx;), $_ for ${qq(\xC5\xB8)}, ${qq(\x{178})}, ${qq(\xC3\xA1)}, ${qq(\xE1)}};
my $byte_args = "-- -\xC5\xB8 -\xC3\xA1=\xE2\x82\xAC";
local $ENV{LC_ALL} = 'C';
local $ENV{LANG} = 'C';

my $without_utf8 = qx{$^X -C0 -s -e '$program' $byte_args 2>&1};
my $without_utf8_status = $? >> 8;
is($without_utf8_status, 0, '-s accepts non-ASCII byte arguments without -CA');
is($without_utf8, '31;;e2.82.ac;;', '-s preserves argument bytes without -CA');

SKIP: {
    # The host's system Perl is 5.42 and predates the upstream -s/-CA behavior
    # covered by perl5_t/t/run/switches.t. Keep this check active under jperl.
    skip 'system Perl predates Unicode decoding for -s arguments', 2
        unless $^X =~ /(?:^|\/)jperl(?:\.bat)?\z/i;

    my $with_utf8 = qx{$^X -CA -s -e '$program' $byte_args 2>&1};
    my $with_utf8_status = $? >> 8;
    is($with_utf8_status, 0, '-s accepts UTF-8 byte arguments with -CA');
    is($with_utf8, ';31;;20ac;', '-CA decodes -s argument bytes as UTF-8');
}

done_testing;
