use strict;
use warnings;
use utf8;
use Test::More;

sub value { defined $_[0] ? $_[0] : '<undef>' }

my $rx = qr/(?:(?<empty>)|(?<char>.))/;

sub collect {
    my ($s, $pattern) = @_;
    my @events;
    pos($s) = 0;
    while ($s =~ /$pattern/g) {
        push @events, [
            value($+{empty}),
            value($+{char}),
            $-[0],
            $+[0],
            pos($s),
        ];
    }
    return (\@events, pos($s));
}

my ($ascii, $ascii_pos) = collect('ab', $rx);
is_deeply(
    $ascii,
    [
        ['',        '<undef>', 0, 0, 0],
        ['<undef>', 'a',       0, 1, 1],
        ['',        '<undef>', 1, 1, 1],
        ['<undef>', 'b',       1, 2, 2],
        ['',        '<undef>', 2, 2, 2],
    ],
    'one empty then one consuming match at each ASCII position',
);
ok(!defined($ascii_pos), 'exhausted non-c global match resets pos');

my ($unicode, $unicode_pos) = collect("\x{1F642}a", $rx);
is_deeply(
    $unicode,
    [
        ['',        '<undef>', 0, 0, 0],
        ['<undef>', "\x{1F642}", 0, 1, 1],
        ['',        '<undef>', 1, 1, 1],
        ['<undef>', 'a',       1, 2, 2],
        ['',        '<undef>', 2, 2, 2],
    ],
    'Unicode spans and pos are character offsets',
);
ok(!defined($unicode_pos), 'exhausted Unicode global match resets pos');

my $captured_unicode = "\x{1F642}a";
ok($captured_unicode =~ /(\x{1F642})(a)/,
    'non-global capture matches supplementary and BMP characters');
is_deeply([@-], [0, 0, 1],
    'non-global capture starts are Perl character offsets');
is_deeply([@+], [2, 1, 2],
    'non-global capture ends are Perl character offsets');

my $replacement_unicode = "\x{1F642}a";
my @replacement_offsets;
$replacement_unicode =~ s{(.)}{do {
    push @replacement_offsets, [$-[0], $+[0]];
    $1;
}}eg;
is_deeply(
    \@replacement_offsets,
    [[0, 1], [1, 2]],
    'Unicode substitution callbacks receive Perl character offsets',
);

my $byte_subject = "\xF0\x9F\x99\x82";
my @byte_offsets;
{
    use bytes;
    $byte_subject =~ /(....)/;
    @byte_offsets = ($-[0], $+[0], $-[1], $+[1]);
}
is_deeply(
    \@byte_offsets,
    [0, 4, 0, 4],
    'byte matches retain byte offsets',
);

my $list_rx = qr/(?:|.)/;
my @list_matches = ('ab' =~ /$list_rx/g);
is_deeply(
    \@list_matches,
    ['', 'a', '', 'b', ''],
    'list-context global match retries consuming alternatives at the same position',
);

my $list_capture_rx = qr/((?:)|.)/;
my @list_captures = ('ab' =~ /$list_capture_rx/g);
is_deeply(
    \@list_captures,
    ['', 'a', '', 'b', ''],
    'list-context global captures publish every empty and consuming result',
);

my @artificial_anchor = ('ab' =~ /(?:|^b)/g);
is_deeply(
    \@artificial_anchor,
    ['', '', ''],
    'global retry does not treat its artificial region start as string start',
);

my @multiline_anchor = ("a\nb" =~ /(?:|^b)/mg);
is_deeply(
    \@multiline_anchor,
    ['', '', '', 'b', ''],
    'global retry preserves a real multiline start anchor',
);

my $multi_rx = qr/(?:(?<first>)|(?<second>(?=a))|(?<letter>a))/;
my $multi = 'a';
my @branches;
while ($multi =~ /$multi_rx/g) {
    my $branch = defined($+{first})   ? 'first'
               : defined($+{second}) ? 'second'
               :                       'letter';
    push @branches, [$branch, $-[0], $+[0], pos($multi)];
}
is_deeply(
    \@branches,
    [
        ['first',  0, 0, 0],
        ['letter', 0, 1, 1],
        ['first',  1, 1, 1],
    ],
    'same-position retry suppresses every second empty alternative',
);

my $state = 'a';
pos($state) = 0;
my @gc;
for (1 .. 4) {
    my $ok = $state =~ /$rx/gc;
    push @gc, $ok
        ? [
            1,
            value($+{empty}),
            value($+{char}),
            $-[0],
            $+[0],
            pos($state),
        ]
        : [0, pos($state)];
}
is_deeply(
    \@gc,
    [
        [1, '',        '<undef>', 0, 0, 0],
        [1, '<undef>', 'a',       0, 1, 1],
        [1, '',        '<undef>', 1, 1, 1],
        [0, 1],
    ],
    'gc preserves pos after failure following terminal empty match',
);
ok(!($state =~ /$rx/g), 'non-c retry at preserved terminal pos fails');
ok(!defined(pos($state)), 'non-c failure resets pos');
ok(
    $state =~ /$rx/g
        && defined($+{empty})
        && $+{empty} eq ''
        && pos($state) == 0,
    'after reset global matching restarts with empty match at zero',
);

my $sub_rx = qr/((?:)|.)/;
my $sub = 'a';
my @sub_events;
my $sub_count = $sub =~ s{$sub_rx}{do {
    push @sub_events, [$1, $-[0], $+[0]];
    '<' . $1 . '>';
}}eg;
is($sub_count, 3, 'substitution performs empty consuming empty sequence');
is($sub, '<><a><>', 'substitution preserves all three replacements in order');
is_deeply(
    \@sub_events,
    [
        ['',  0, 0],
        ['a', 0, 1],
        ['',  1, 1],
    ],
    'substitution callbacks observe original same-position spans',
);

my $quoted = qr/(?:'[^']*'|"[^"]*")*/;
my $sql = q{SELECT '(??)', (??)};
my $replacements = 0;
$sql =~ s[($quoted|\(\?\?\))] {
    $1 eq '(??)'
        ? do {
            ++$replacements;
            '(?, ?)';
        }
        : $1
}eg;
is(
    $sql,
    q{SELECT '(??)', (?, ?)},
    'DBIx substitution leaves quoted marker and replaces unquoted marker',
);
is(
    $replacements,
    1,
    'DBIx substitution invokes nonempty omniholder branch once',
);

done_testing;
