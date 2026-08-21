use strict;
use warnings;
use utf8;

use Test::More tests => 17;

sub no_boundary {
    my ($left, $right, $description) = @_;
    my $subject = $left . $right;
    ok($subject =~ /\A\Q$left\E\B{wb}\Q$right\E\z/, $description);
}

sub boundary {
    my ($left, $right, $description) = @_;
    my $subject = $left . $right;
    ok($subject =~ /\A\Q$left\E\b{wb}\Q$right\E\z/, $description);
}

no_boundary("\r", "\r", 'CR x CR is not a word boundary');
no_boundary("\r", "\n", 'CR x LF is not a word boundary');
no_boundary("\n", "\r", 'LF x CR is not a word boundary');
no_boundary("\n", "\n", 'LF x LF is not a word boundary');
no_boundary("\r", "\x0b", 'CR x vertical tab is not a word boundary');
no_boundary("\x0b", "\r", 'vertical tab x CR is not a word boundary');
no_boundary("\x0b", "\x0b", 'vertical tab x vertical tab is not a word boundary');
no_boundary("\x{85}", "\x{2028}", 'NEL x line separator is not a word boundary');
no_boundary("\x{2028}", "\x{2029}", 'line separator x paragraph separator is not a word boundary');

boundary("\r", "A", 'newline x letter is a word boundary');
boundary("A", "\r", 'letter x newline is a word boundary');
boundary("\x0b", "_", 'control newline x ExtendNumLet is a word boundary');

ok("\r" =~ /\A\b{wb}\r\b{wb}\z/, 'start and end remain word boundaries');
ok("\r\r" !~ /\A\B{wb}/, 'negated word boundary rejects start of string');
ok("\r\r" !~ /\B{wb}\z/, 'negated word boundary rejects end of string');

my $subject = "\r\rA";
my @boundaries;
while ($subject =~ /\b{wb}/g) {
    push @boundaries, pos($subject);
}
is_deeply(\@boundaries, [0, 2, 3],
    'repeated boundary scans skip the interior newline-run position');

my @non_boundaries;
while ($subject =~ /\B{wb}/g) {
    push @non_boundaries, pos($subject);
}
is_deeply(\@non_boundaries, [1],
    'repeated negated-boundary scans find only the newline-run interior');
