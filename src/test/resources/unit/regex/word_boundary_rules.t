use strict;
use warnings;
use utf8;
use Test::More tests => 33;

sub boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\b{wb}${right}\z/;
}

sub no_boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\B{wb}${right}\z/;
}

ok(boundary_at('ab', 0), 'WB1 start of text is a boundary');
ok(boundary_at('ab', 2), 'WB2 end of text is a boundary');
ok(no_boundary_at("\r\n", 1), 'WB3 keeps CR LF together');
ok(boundary_at("\rA", 1), 'WB3a breaks after CR');
ok(boundary_at("A\n", 1), 'WB3b breaks before LF');
ok(no_boundary_at("\x{200D}\x{1F680}", 1), 'WB3c keeps ZWJ with pictograph');
ok(boundary_at("\x{200D}\x{0308}\x{1F680}", 2),
    'WB3c does not skip Extend between ZWJ and pictograph');
ok(no_boundary_at('  ', 1), 'WB3d keeps horizontal spaces together');
ok(boundary_at(" \x{0308} ", 2), 'WB3d requires adjacent horizontal spaces');
ok(no_boundary_at("A\x{0308}", 1), 'WB4 ignores Extend');
ok(no_boundary_at('ab', 1), 'WB5 keeps letters together');
ok(no_boundary_at('a.b', 1), 'WB6 keeps MidNumLet after a letter');
ok(no_boundary_at('a.b', 2), 'WB7 keeps MidNumLet before a letter');
ok(no_boundary_at("\x{05D0}'", 1), 'WB7a keeps Hebrew before single quote');
ok(no_boundary_at("\x{05D0}\"\x{05D1}", 1), 'WB7b keeps Hebrew before double quote');
ok(no_boundary_at("\x{05D0}\"\x{05D1}", 2), 'WB7c keeps Hebrew after double quote');
ok(no_boundary_at('12', 1), 'WB8 keeps numeric characters together');
ok(no_boundary_at('1,2', 1), 'WB12 keeps numeric punctuation after a number');
ok(no_boundary_at('1,2', 2), 'WB11 keeps numeric punctuation before a number');
ok(no_boundary_at('a1', 1), 'WB9 keeps letters before numeric characters');
ok(no_boundary_at('1a', 1), 'WB10 keeps numeric characters before letters');
ok(no_boundary_at("\x{30A2}\x{30A4}", 1), 'WB13 keeps Katakana together');
ok(no_boundary_at('a_', 1), 'WB13a keeps ExtendNumLet after a letter');
ok(no_boundary_at('_a', 1), 'WB13b keeps ExtendNumLet before a letter');
ok(no_boundary_at("\x{1F1E6}\x{1F1E7}", 1), 'WB15 keeps the first RI pair');
ok(boundary_at("\x{1F1E6}\x{1F1E7}\x{1F1E8}", 2), 'WB16 breaks before a third RI');
ok(boundary_at('a,b', 1), 'WB999 breaks before unrelated punctuation');
ok(boundary_at('a,b', 2), 'WB999 breaks after unrelated punctuation');
ok(!boundary_at('ab', 1), 'positive assertion rejects a non-boundary');
ok(!no_boundary_at('a,b', 1), 'negated assertion rejects a boundary');
ok(no_boundary_at("a\x{00AD}b", 1), 'WB4 ignores Format before it');
ok(no_boundary_at("a\x{00AD}b", 2), 'Format is transparent to letter context');

{
    use bytes;
    ok('ab' =~ /\Aa\B{wb}b\z/, 'byte-mode ASCII uses word boundaries');
}
