use strict;
use warnings;
use utf8;
use Test::More;

my @status = (
    [Allowed => 0x0041],
    [Restricted => 0x0000],
);

my @type = (
    [Not_Character => 0x0000],
    [Deprecated => 0x0149],
    [Default_Ignorable => 0x00AD],
    [Not_NFKC => 0x00A0],
    [Not_XID => 0x02EA],
    [Exclusion => 0x18A9],
    [Obsolete => 0x07E8],
    [Technical => 0x0740],
    [Uncommon_Use => 0xA9CF],
    [Limited_Use => 0x0710],
    [Inclusion => 0x0027],
    [Recommended => 0x0030],
);

sub compiles {
    my ($source) = @_;
    local $SIG{__WARN__} = sub { };
    return defined eval "qr/$source/";
}

sub matches {
    my ($char, $source) = @_;
    local $SIG{__WARN__} = sub { };
    my $rx = eval "qr/$source/";
    return defined($rx) && $char =~ $rx;
}

SKIP: {
skip 'Identifier properties require Perl 5.44 Unicode 17 data', 40 if $] < 5.044;

TODO: {
local $TODO = 'Identifier property resolver wiring is a separate Phase 36 slice';

for my $entry (@status) {
    my ($value, $cp) = @$entry;
    ok(matches(chr($cp), "\\p{ID_Status=$value}"),
        "ID_Status=$value contains U+" . sprintf('%04X', $cp));
    ok(matches(chr($cp), "\\p{Identifier_Status=$value}"),
        "Identifier_Status=$value contains U+" . sprintf('%04X', $cp));
}

for my $entry (@type) {
    my ($value, $cp) = @$entry;
    ok(matches(chr($cp), "\\p{ID_Type=$value}"),
        "ID_Type=$value contains U+" . sprintf('%04X', $cp));
    ok(matches(chr($cp), "\\p{Identifier_Type=$value}"),
        "Identifier_Type=$value contains U+" . sprintf('%04X', $cp));
}

ok(matches("A", '\\p{ i d-s_t a t u s = a l-l_o w e d }'),
    'status property and value use loose matching');
ok(matches("A", '\\p{ i d-t_y p e = r e-c_o m m e n d e d }'),
    'type property and value use loose matching');
ok(matches("\x{018D}", '\\p{ID_Type=Technical}'),
    'overlap witness has Technical type');
ok(matches("\x{018D}", '\\p{ID_Type=Obsolete}'),
    'overlap witness also has Obsolete type');
ok(matches("\x{1CC0}", '\\p{ID_Type=Limited_Use}'),
    'second overlap witness has Limited_Use type');
ok(matches("\x{1CC0}", '\\p{ID_Type=Not_XID}'),
    'second overlap witness also has Not_XID type');
ok(!matches('A', '\\p{ID_Type=Not_Character}'),
    'explicit type member is excluded from Not_Character default');
ok(matches("\x{0}", '\\p{ID_Type=Not_Character}'),
    'unlisted code point receives Not_Character default');
ok(!compiles('\\p{ID_Status=Unknown_Value}'), 'unknown status value is rejected');
ok(!compiles('\\p{ID_Type=Unknown_Value}'), 'unknown type value is rejected');
ok(!compiles("\\p{Identifier\x{1680}Type=Recommended}"),
    'Unicode whitespace is not ignored by loose matching');
ok(matches("\x{11B60}", '\\p{ID_Type=Exclusion}'),
    'Unicode 17 Sharada supplement exclusion');

}
}

done_testing;
