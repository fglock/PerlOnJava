use strict;
use warnings;
use Test::More tests => 8;

my $d = qr/Good/;
my $d1 = $d;
my $control = qr/Good/;
$$d = 'Bad';
is($$d, 'Bad', 'dereferencing a qr object writes its scalar pattern');
is($$d1, 'Bad', 'copies of a qr object share the pattern scalar');
like("$control", qr/Good/, 'an independent qr object keeps its own pattern');
like("$d", qr/\ARegexp=SCALAR\(0x[0-9a-f]+\)\z/,
    'stringifying a modified qr object uses its scalar reference form');

my $blessed = bless qr/Faux Pie/, 'Stew';
$$blessed = 'Fake!';
is($$blessed, 'Fake!', 'dereferencing a blessed qr object writes its pattern');
like("$blessed", qr/\AStew=SCALAR\(0x[0-9a-f]+\)\z/,
    'a modified blessed qr object retains its class');

sub {
    $_[0] = ${qr=crumpets=};
    is(ref \$_[0], 'REGEXP', 'a regex lvalue keeps its special reference type');
    my $copy = $_[0];
    is(ref \$copy, 'REGEXP', 'copying a regex lvalue keeps its special type');
}->((\my %hash)->{key});
