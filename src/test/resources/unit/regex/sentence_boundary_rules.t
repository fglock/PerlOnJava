use strict;
use warnings;
use utf8;
use Test::More tests => 23;

sub boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\b{sb}${right}\z/;
}

sub no_boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\B{sb}${right}\z/;
}

ok(boundary_at('ab', 0), 'SB1 start of text is a boundary');
ok(boundary_at('ab', 2), 'SB2 end of text is a boundary');
ok(no_boundary_at("\r\n", 1), 'SB3 keeps CR LF together');
ok(boundary_at("\rA", 1), 'SB4 breaks after CR');
ok(no_boundary_at("A\x{0308}", 1), 'SB5 ignores Extend before sentence breaking');
ok(no_boundary_at("A\x{200C}", 1), 'SB5 ignores Format before sentence breaking');
ok(no_boundary_at('.1', 1), 'SB6 keeps ATerm before Numeric');
ok(no_boundary_at('A.B', 2), 'SB7 keeps an initial before Upper');
ok(no_boundary_at('A. a', 3), 'SB8 keeps ATerm and spaces before Lower');
ok(no_boundary_at('A.) a', 4), 'SB8 skips Close and Sp before Lower');
ok(no_boundary_at("etc.)\x{2019}\x{a0}\x{2018}(the", 7),
    'SB8 looks through trailing Close characters before Lower');
ok(no_boundary_at('A. ,', 3), 'SB8a keeps sentence continuation punctuation');
ok(no_boundary_at('A.)', 2), 'SB9 keeps Close after a terminal');
ok(no_boundary_at('A.  ', 3), 'SB10 keeps consecutive spaces after a terminal');
ok(boundary_at('A. B', 3), 'SB11 breaks after ATerm and Sp');
ok(boundary_at('A!B', 2), 'SB11 breaks after STerm');
ok(boundary_at('A!a', 2), 'SB8 does not suppress STerm before Lower');
ok(boundary_at("A!\x{0308}B", 3), 'ignored characters preserve terminal context');
ok(no_boundary_at("A.\n\x{0308}B", 4),
    'ignored characters after a paragraph break do not reopen SB11 context');
ok(no_boundary_at('AB', 1), 'SB998 keeps ordinary text in one sentence');
ok(!boundary_at('AB', 1), 'positive assertion rejects a non-boundary');
ok(!no_boundary_at('A!B', 2), 'negated assertion rejects a sentence boundary');

{
    use bytes;
    ok('A!B' =~ /\AA!\b{sb}B\z/, 'byte-mode ASCII uses sentence boundaries');
}
