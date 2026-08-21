#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 13;
no warnings 'experimental::uniprop_wildcards';

eval q{qr/\p{nv:(\B(*COMMIT)C+)}/};
like($@, qr/No Unicode property value wildcard matches/,
    'pat_advanced:1663 wildcard control verb reports no match');

eval q{qr/\p{upper:]}|\337(?|ss)|)(?0/};
like($@, qr/Unicode property wildcard not terminated/,
    'pat_advanced:1664 single-character wildcard is unterminated');

eval q{qr/\p{utf8::_perl_surrogate}/};
is($@, '', 'pat_advanced:1685 runtime utf8 surrogate property compiles');

my @delimiter_matrix = (
    [q{qr/\p{nv=(\A1\z)}/}, '1', 'numeric paired parentheses'],
    [q{qr/\p{Block=[\ABasic_Latin\z]}/}, 'A', 'Block paired brackets'],
    [q{qr/\p{name=<\ALATIN CAPITAL LETTER A\z>}/}, 'A',
        'Name paired angle brackets'],
    [q{qr/\p{Age=#\AV2_1\z#}/}, "\x{20ac}",
        'Age repeated hash delimiter'],
);
for my $case (@delimiter_matrix) {
    my ($source, $member, $label) = @$case;
    my $pattern = eval $source;
    is($@, '', "$label compiles");
    like($member, $pattern, "$label matches");
}

my $escaped_delimiter = eval q{qr/\p{nv=\#\A1\z\#}/};
is($@, '', 'escaped repeated delimiter compiles');
like('1', $escaped_delimiter, 'escaped repeated delimiter matches');
