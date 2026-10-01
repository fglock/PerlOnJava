use strict;
use warnings;
use Test::More;

my $d = qr/Good/;
my $d1 = $d;
$$d = 'Bad';
is $$d, 'Bad', 'dereferenced qr scalar accepts assignment';
is $$d1, 'Bad', 'copied qr values share the updated regexp scalar';
like "$d", qr/\ARegexp=SCALAR\(0x[0-9a-f]+\)\z/,
    'assigned regexp scalar stringifies as a scalar reference';

my $e = bless qr/Faux Pie/, 'Stew';
$$e = 'Fake!';
is $$e, 'Fake!', 'blessed qr scalar accepts assignment';
is ref($e), 'Stew', 'assignment preserves the regexp object class';
like "$e", qr/\AStew=SCALAR\(0x[0-9a-f]+\)\z/,
    'assigned blessed regexp stringifies as a scalar reference';

sub {
    $_[0] = ${qr=crumpets=};
    is ref \$_[0], 'REGEXP', 'regexp lvalue keeps its special reference type';
    my $copy = $_[0];
    is ref \$copy, 'REGEXP', 'copying a regexp lvalue keeps its special type';
} -> ((\my %hash)->{key});

done_testing();
