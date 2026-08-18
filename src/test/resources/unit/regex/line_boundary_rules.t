use strict;
use warnings;
use utf8;
use Test::More tests => 84;

sub has_boundary {
    my ($left, $right) = @_;
    my $text = $left . $right;
    return $text =~ /\A\Q$left\E\b{lb}\Q$right\E\z/u;
}

sub has_nonboundary {
    my ($left, $right) = @_;
    my $text = $left . $right;
    return $text =~ /\A\Q$left\E\B{lb}\Q$right\E\z/u;
}

sub boundary {
    my ($left, $right, $rule) = @_;
    ok(has_boundary($left, $right), "$rule permits a boundary");
    ok(!has_nonboundary($left, $right), "$rule rejects the inverse");
}

sub nonboundary {
    my ($left, $right, $rule) = @_;
    ok(!has_boundary($left, $right), "$rule rejects a boundary");
    ok(has_nonboundary($left, $right), "$rule accepts the inverse");
}

nonboundary('', 'A', 'LB2 start of text');
boundary('A', '', 'LB3 end of text');
boundary("\x{B}", 'A', 'LB4 mandatory break');
nonboundary("\r", "\n", 'LB5 CR LF pair');
boundary("\r", 'A', 'LB5 after CR');
nonboundary('A', "\n", 'LB6 before hard break');
nonboundary('A', ' ', 'LB7 before space');
boundary("\x{200B} ", 'A', 'LB8 after zero-width space');
nonboundary("\x{200D}", 'A', 'LB8a after ZWJ');
nonboundary('A', "\x{0308}", 'LB9 combining sequence');
boundary(' ', "\x{0308}", 'LB10 leading combining mark becomes AL');
nonboundary('A', "\x{2060}", 'LB11 before word joiner');
nonboundary("\x{2060}", 'A', 'LB11 after word joiner');
nonboundary("\x{00A0}", 'A', 'LB12 after glue');
nonboundary('A', "\x{00A0}", 'LB12a before glue');
boundary(' ', "\x{00A0}", 'LB12a space overrides glue');
nonboundary('A ', ']', 'LB13 before closing punctuation');
nonboundary('( ', 'A', 'LB14 after opening punctuation');
boundary(' ', '.5', 'LB15c space before infix-number sequence');
nonboundary('A ', ',', 'LB15d before infix separator');
nonboundary(') ', "\x{3041}", 'LB16 closing punctuation before nonstarter');
nonboundary("\x{2014} ", "\x{2014}", 'LB17 break-both pair');
boundary('A ', 'B', 'LB18 after space');
nonboundary('A', '"', 'LB19 before unresolved quote');
boundary('A', "\x{FFFC}", 'LB20 before contingent break');
boundary("\x{FFFC}", 'A', 'LB20 after contingent break');
nonboundary(' -', 'A', 'LB20a word-initial hyphen suppression');
nonboundary('A', '-', 'LB21 before hyphen');
nonboundary('A', "\x{2026}", 'LB22 before inseparable ellipsis');
nonboundary('A', '1', 'LB23 letter digit');
nonboundary('$', "\x{4E2D}", 'LB23a prefix ideograph');
nonboundary('$', 'A', 'LB24 prefix alphabetic');
nonboundary('12,', '3', 'LB25 numeric expression');
nonboundary("\x{1100}", "\x{1161}", 'LB26 Hangul syllable');
nonboundary('A', 'B', 'LB28 alphabetic word');
nonboundary("\x{11003}", "\x{1B05}", 'LB28a Brahmic syllable');
nonboundary('.', 'A', 'LB29 numeric punctuation alphabetic');
nonboundary('A', '(', 'LB30 alphabetic before parenthesis');
nonboundary("\x{1F1E6}", "\x{1F1E7}", 'LB30a odd regional-indicator prefix');
boundary("\x{1F1E6}\x{1F1E7}", "\x{1F1E8}", 'LB30a even regional-indicator prefix');
nonboundary("\x{261D}", "\x{1F3FB}", 'LB30b emoji base modifier');
boundary("\x{4E2D}", "\x{6587}", 'LB31 default ideograph break');
