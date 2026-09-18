use strict;
use warnings;
use Test::More;

for my $case (
    [q{my (our $x);},            'our',   'my',    '(our'],
    [q{our (my $x);},            'my',    'our',   '(my'],
    [q{my (my $x);},             'my',    'my',    '(my'],
    [q{our ($x, our($y), $z);},  'our',   'our',   ', our'],
) {
    my ($source, $nested, $outer, $near) = @$case;
    my $ok = eval $source;
    ok(!$ok, "$source fails");
    like($@, qr{\ACan't redeclare "\Q$nested\E" in "\Q$outer\E" at \(eval \d+\) line 1, near "\Q$near\E"},
         "$source identifies both declarations and context");
}

done_testing;
