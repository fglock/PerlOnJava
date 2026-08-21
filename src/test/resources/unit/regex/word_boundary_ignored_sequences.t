use strict;
use warnings;
use utf8;

use Test::More tests => 27;

ok("  \r" =~ /\A \B{wb} \B{wb}\r\z/,
    'horizontal whitespace joins a following vertical whitespace run');
ok("  \r" !~ /\A \B{wb} \b{wb}\r\z/,
    'positive boundary rejects the interior whitespace transition');

ok("  \r\x{0308}" =~ /\A \B{wb} \B{wb}\r\b{wb}\x{0308}\z/,
    'vertical whitespace breaks before ignored Extend');
ok("  \r\x{00ad}" =~ /\A \B{wb} \B{wb}\r\b{wb}\x{00ad}\z/,
    'vertical whitespace breaks before ignored Format');
ok("  \r\x{200d}" =~ /\A \B{wb} \B{wb}\r\b{wb}\x{200d}\z/,
    'vertical whitespace breaks before ignored ZWJ');

ok("   \x{0308}" =~ /\A \B{wb} \b{wb} \B{wb}\x{0308}\z/,
    'horizontal whitespace and Extend form a pair separate from its prefix');
ok("   \x{00ad}" =~ /\A \B{wb} \b{wb} \B{wb}\x{00ad}\z/,
    'horizontal whitespace and Format form a pair separate from its prefix');
ok("  \t\x{200d}" =~ /\A \B{wb} \B{wb}\t\B{wb}\x{200d}\z/,
    'horizontal tab and ZWJ remain joined to the whitespace prefix');
ok("  \t\x{0308}" =~ /\A \B{wb} \b{wb}\t\B{wb}\x{0308}\z/,
    'horizontal tab binds to Extend separately from its prefix');
ok("   \x{200d}" =~ /\A \B{wb} \B{wb} \B{wb}\x{200d}\z/,
    'ZWJ remains joined to the complete WSegSpace run');
ok("  \x{00a0}\x{200d}"
        =~ /\A \B{wb} \B{wb}\x{00a0}\B{wb}\x{200d}\z/,
    'tailored no-break space remains joined before ZWJ');
ok("  \x{2007}\x{200d}"
        =~ /\A \B{wb} \B{wb}\x{2007}\B{wb}\x{200d}\z/,
    'tailored figure space remains joined before ZWJ');
ok("  \x{202f}\x{200d}"
        =~ /\A \B{wb} \b{wb}\x{202f}\B{wb}\x{200d}\z/,
    'ExtendNumLet narrow no-break space retains its non-tailored boundary');
ok("  \x{0308}" =~ /\A \b{wb} \B{wb}\x{0308}\z/,
    'a WSegSpace before Extend starts a new pair after its prefix');
ok("\r \x{0308}" =~ /\A\r\B{wb} \B{wb}\x{0308}\z/,
    'newline and WSegSpace remain joined when ignored text follows');
ok("  \x{1680}\x{0308}"
        =~ /\A \B{wb} \b{wb}\x{1680}\B{wb}\x{0308}\z/,
    'non-ASCII WSegSpace follows the same ignored-suffix rule');
ok("\x{1680}\r" =~ /\A\x{1680}\B{wb}\r\z/,
    'non-ASCII WSegSpace joins a newline property');

ok("\x{1f469}\x{0308}\x{200d}\x{1f680}"
        =~ /\A\x{1f469}\B{wb}\x{0308}\B{wb}\x{200d}\B{wb}\x{1f680}\z/,
    'Extend and ZWJ remain transparent in an emoji ZWJ sequence');
ok("\x{1f1e6}\x{0308}\x{1f1e7}"
        =~ /\A\x{1f1e6}\B{wb}\x{0308}\B{wb}\x{1f1e7}\z/,
    'ignored Extend preserves odd RI parity');
ok("\x{1f1e6}\x{1f1e7}\x{0308}\x{1f1e8}"
        =~ /\A\x{1f1e6}\B{wb}\x{1f1e7}\B{wb}\x{0308}\b{wb}\x{1f1e8}\z/,
    'ignored Extend preserves the break before a third RI');

ok("\r\x{0308}\n" =~ /\A\r\b{wb}\x{0308}\b{wb}\n\z/,
    'ignored Extend does not bridge two vertical whitespace characters');
ok("\x{0308}A" =~ /\A\x{0308}\b{wb}A\z/,
    'leading ignored character remains separated at start');
ok("A\x{0308}" =~ /\AA\B{wb}\x{0308}\z/,
    'trailing ignored character follows WB4 at end');

my $spaces = "   \x{0308}A";
my @space_boundaries;
while ($spaces =~ /\b{wb}/g) {
    push @space_boundaries, pos($spaces);
}
is_deeply(\@space_boundaries, [0, 2, 4, 5],
    'repeated scans preserve whitespace-prefix and ignored-pair boundaries');

my @space_non_boundaries;
while ($spaces =~ /\B{wb}/g) {
    push @space_non_boundaries, pos($spaces);
}
is_deeply(\@space_non_boundaries, [1, 3],
    'repeated negated scans find both whitespace non-boundaries');

ok("  \rA" =~ /(?:\A \B{wb} X|\A \B{wb} \B{wb}\r\b{wb}A\z)/,
    'backtracking from a failed boundary branch restores the successful path');
ok("  \rA" !~ /(?:\A \B{wb} \b{wb}\rA|\A \b{wb} \B{wb}\rA)/,
    'failed alternatives do not leak boundary state');
