use strict;
use warnings;
use Test::More tests => 10;
use re 'eval';

our @seen;
my $inner = qr{
    (1)
    ((??{
        push @seen, defined $^N ? $^N : 'undef';
        1 + $^N;
    })){2}
    (?{ $^N })
    (|a(b)c|def)
    (??{ "$^R" })
}x;

undef $^R;
my $matched = '123abc3' =~ /^($inner)$/;
ok($matched, 'embedded executable qr matches');
is(join(',', @seen), '1,2', 'embedded callbacks see shifted captures');
is($1, '123abc3', 'outer capture spans the embedded qr');
is($2, '1', 'first embedded capture is renumbered');
is($3, '3', 'repeated embedded capture publishes its final value');
is($4, 'abc', 'embedded alternation capture is renumbered');
is($5, 'b', 'nested embedded capture is renumbered');
is($^R, '3', 'embedded block result survives the nested matcher');

our $runtime_qr = $inner;
undef $^R;
ok('123abc3' =~ /^(??{$runtime_qr})$/,
    'runtime qr containing callbacks matches through a dynamic group');
is($^R, '3', 'runtime qr block result survives its outer dynamic callback');
