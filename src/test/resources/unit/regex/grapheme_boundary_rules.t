use strict;
use warnings;
use utf8;
use Test::More tests => 29;

sub boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\b{gcb}${right}\z/;
}

sub no_boundary_at {
    my ($subject, $offset) = @_;
    my $left = quotemeta substr($subject, 0, $offset);
    my $right = quotemeta substr($subject, $offset);
    return $subject =~ /\A${left}\B{gcb}${right}\z/;
}

ok(boundary_at('ab', 0), 'GB1 start of text is a boundary');
ok(boundary_at('ab', 2), 'GB2 end of text is a boundary');
ok(no_boundary_at("\r\n", 1), 'GB3 keeps CR LF together');
ok(boundary_at("\rA", 1), 'GB4 breaks after CR');
ok(boundary_at("A\n", 1), 'GB5 breaks before LF');
ok(no_boundary_at("\x{1100}\x{1161}", 1), 'GB6 keeps Hangul L before V');
ok(no_boundary_at("\x{AC00}\x{11A8}", 1), 'GB7 keeps Hangul LV before T');
ok(no_boundary_at("\x{AC01}\x{11A8}", 1), 'GB8 keeps Hangul LVT before T');
ok(no_boundary_at("A\x{0308}", 1), 'GB9 keeps Extend with its base');
ok(no_boundary_at("A\x{200D}", 1), 'GB9 keeps ZWJ with its base');
ok(no_boundary_at("A\x{0903}", 1), 'GB9a keeps SpacingMark with its base');
ok(no_boundary_at("\x{0600}A", 1), 'GB9b keeps Prepend with following text');
ok(no_boundary_at("\x{0915}\x{094D}\x{0915}", 2),
    'GB9c keeps Indic conjunct linker sequence together');
ok(no_boundary_at("\x{1F469}\x{0308}\x{200D}\x{1F680}", 3),
    'GB11 keeps extended pictograph ZWJ sequence together');
ok(no_boundary_at("\x{1F1E6}\x{1F1E7}", 1),
    'GB12 keeps the first regional-indicator pair together');
ok(boundary_at("\x{1F1E6}\x{1F1E7}\x{1F1E8}", 2),
    'GB12 breaks before an odd trailing regional indicator');
ok(boundary_at('AB', 1), 'GB999 breaks between ordinary letters');
ok(!no_boundary_at('AB', 1), 'negated assertion rejects a real boundary');

for my $subject (
    "\x{0915}\x{094D}\x{0924}",
    "\x{0915}\x{094D}\x{094D}\x{0924}",
    "\x{0915}\x{094D}\x{200D}\x{0924}",
    "\x{0915}\x{093C}\x{200D}\x{094D}\x{0924}",
    "\x{0915}\x{094D}\x{0924}\x{094D}\x{092F}",
    "\x{0915}\x{094D}\x{200D}\x{0924}\x{094D}\x{200D}\x{092F}",
    "\x{1004}\x{103A}\x{1039}\x{1011}\x{1039}\x{1011}",
    "\x{1B32}\x{1B44}\x{1B22}\x{1B44}\x{1B2C}",
    "\x{179F}\x{17D2}\x{178F}\x{17D2}\x{179A}\x{17B8}",
) {
    is(($subject =~ /\A(\X)\z/)[0], $subject,
        '\\X consumes the complete GB9c Indic conjunct sequence');
}

{
    use bytes;
    ok("AB" =~ /\AA\b{gcb}B\z/, 'byte-mode ASCII letters have a GCB boundary');
    ok("\r\n" =~ /\A\r\B{gcb}\n\z/, 'byte-mode CR LF remains one cluster');
}
