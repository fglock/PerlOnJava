use strict;
use warnings;
use Test::More;

for my $code_point (0x0558, 0x058B, 0x058C, 0x208F, 0x209D, 0x209E,
                    0x209F, 0xA7DD, 0xA7E2, 0xAB6C, 0xAB6D) {
    my $character = chr $code_point;
    my $ok = eval "my \${character} = 1; \${character}";
    is($@, '', sprintf 'U+%04X is accepted as a Perl identifier start', $code_point);
    is($ok, 1, sprintf 'U+%04X identifier retains its value', $code_point);
}

my $bad_character = chr 0x0557;
my $bad = eval "my \$$bad_character = 1;";
ok(!defined $bad, 'adjacent non-XID_Start code point remains rejected');
like($@, qr/(?:Unrecognized character|syntax error|Can't use global)/, 'rejected identifier reports a lexer error');

done_testing;
