use strict;
use warnings;
use Test::More tests => 6;

for my $mask_position (0 .. 2) {
    eval {
        $mask_position == 0 ? select('a', '', '', 0)
      : $mask_position == 1 ? select('', 'a', '', 0)
                            : select('', '', 'a', 0)
    };
    like($@, qr/^Modification of a read-only value attempted/,
         "select rejects a read-only mask in position $mask_position");
}

for my $mask_position (0 .. 2) {
    my $wide = "\x{100}";
    eval {
        $mask_position == 0 ? select($wide, undef, undef, 0)
      : $mask_position == 1 ? select(undef, $wide, undef, 0)
                            : select(undef, undef, $wide, 0)
    };
    like($@, qr/^Wide character in select/,
         "select rejects a wide-character mask in position $mask_position");
}
