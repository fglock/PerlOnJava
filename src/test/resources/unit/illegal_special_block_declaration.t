use strict;
use warnings;
use Test::More;

for my $block (qw(BEGIN CHECK INIT UNITCHECK END)) {
    my $ok = eval "$block <>";
    ok(!$ok, "$block diamond declaration fails");
    like($@, qr{\AIllegal declaration of subroutine \Q$block\E at \(eval \d+\) line 1\.},
         "$block reports the special-block declaration error");
}

done_testing;
