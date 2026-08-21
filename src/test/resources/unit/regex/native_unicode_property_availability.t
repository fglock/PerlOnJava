use strict;
use warnings;
use Test::More tests => 16;

for my $case (
    [q{\p{jg=Ain}},              "\x{0639}", 'Joining_Group'],
    [q{\p{ea=W}},                "\x{1100}", 'East_Asian_Width'],
    [q{\p{bc=L}},                'A',          'Bidi_Class'],
    [q{\p{sc=Latin}},            'A',          'Script'],
    [q{\p{scx=Latin}},           'A',          'Script_Extensions'],
    [q{\p{Joining_Group=Ain}},   "\x{0639}", 'long Joining_Group'],
    [q{\p{East_Asian_Width=Wide}}, "\x{1100}", 'long East_Asian_Width'],
    [q{\p{Bidi_Class=Left_To_Right}}, 'A',     'long Bidi_Class'],
) {
    my ($source, $member, $label) = @$case;
    my $pattern = eval "qr/$source/";
    ok(defined $pattern, "$label compiles without a capability skip")
        or diag($@);
    like($member, $pattern, "$label matches its representative");
}
