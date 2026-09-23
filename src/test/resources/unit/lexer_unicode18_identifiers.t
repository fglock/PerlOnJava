use strict;
use warnings;
use Test::More;

BEGIN {
    # The host system Perl on older supported environments can ship Unicode
    # data predating these Unicode 18 XID_Start additions.  PerlOnJava uses
    # the synchronized core tables even while its reported UCD version lags.
    unless ($^X =~ m{(?:^|/)jperl(?:-exec)?$}) {
        require Unicode::UCD;
        my ($major) = Unicode::UCD::UnicodeVersion() =~ /^(\d+)/;
        plan skip_all => 'system Perl Unicode data predates Unicode 18 XID_Start'
                if !defined($major) || $major < 18;
    }
}

for my $code_point (0x0558, 0x058B, 0x058C, 0x208F, 0x209D, 0x209E,
                    0x209F, 0xA7DD, 0xA7E2, 0xAB6C, 0xAB6D) {
    my $character = chr $code_point;
    my $ok = eval "my \$$character = 1; \$$character";
    is($@, '', sprintf 'U+%04X is accepted as a Perl identifier start', $code_point);
    is($ok, 1, sprintf 'U+%04X identifier retains its value', $code_point);
}

my $bad_character = chr 0x0557;
my $bad = eval "my \$$bad_character = 1;";
ok(!defined $bad, 'adjacent non-XID_Start code point remains rejected');
like($@, qr/(?:Unrecognized character|syntax error|Can't use global)/, 'rejected identifier reports a lexer error');

done_testing;
