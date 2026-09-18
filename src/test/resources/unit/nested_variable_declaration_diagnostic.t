use strict;
use warnings;
use Test::More;

for my $case (
    [q{my (our $x);},   'our',   'my'],
    [q{our (my $x);},   'my',    'our'],
    [q{my (my $x);},    'my',    'my'],
) {
    my ($source, $nested, $outer) = @$case;
    my $ok = eval $source;
    ok(!$ok, "$source fails");
    like($@, qr{\ACan't redeclare "\Q$nested\E" in "\Q$outer\E" at \(eval \d+\) line 1},
         "$source identifies both declarations");
}

done_testing;
