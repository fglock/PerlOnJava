use strict;
use warnings;
use Test::More;

{
local $" = '-';

my $A = 'A';
my $B = 'B';
my $C = 'C';
my $E = 'E';
my $a = 'aa';
my $b = 'bb';
my $e = 'ee';
my @r = (qr/(??{$B})/);

{
    my $B = 'Q';
    push @r, qr/(??{$B})/;
}

{
    package Local::LcConcat;
    use overload
        '""' => sub { ${$_[0]} },
        '.' => sub {
            my ($x, $y) = @_[ $_[2] ? (1, 0) : (0, 1) ];
            my ($xx, $yy) = ("$x", "$y");
            lc("$xx=$yy");
        };
}

my $r = qr/(??{$E})/;
bless $r, 'Local::LcConcat';

use re 'eval';

like('=ee', qr/^$r$/, 'overloaded scalar keeps executable qr source');
{
    no re 'eval';
    eval q{ my $x = qr/^$r$/; 1 };
    like($@, qr/Eval-group not allowed/,
        'overloaded scalar runtime source still requires re eval');
}
like('aa=ee', qr/^(??{$A})$r$/, 'literal callback precedes overloaded scalar');
like('xaa=ee', qr/^X(??{$A})$r$/, 'literal prefix precedes overloaded scalar');
my $pattern = qr/^X(??{$A})$r(??{$C})$/;
like('xaa=eeC', $pattern, 'callbacks surround overloaded scalar');
unlike('', $pattern, 'surrounded overloaded scalar rejects empty subject');
unlike('XA=EC', $pattern, 'surrounded overloaded scalar preserves case transform');

push @r, $r;

like('bb-bb-=ee', qr/^@r$/, 'array carries overloaded executable scalar');
{
    no re 'eval';
    eval q{ my $x = qr/^@r$/; 1 };
    like($@, qr/Eval-group not allowed/,
        'overloaded array runtime source still requires re eval');
}
SKIP: {
    skip 'overloaded executable array interpolation requires Perl 5.44', 3
        if $^V lt v5.44;
    like('aabb-bb-=ee', qr/^(??{$A})@r$/,
        'literal callback precedes overloaded array');
    like('xaabb-bb-=ee', qr/^X(??{$A})@r$/,
        'literal prefix precedes overloaded array');
    $pattern = qr/^X(??{$A})@r(??{$C})$/;
    like('xaabb-bb-=eeC', $pattern, 'callbacks surround overloaded array');
}
$pattern = qr/^X(??{$A})@r(??{$C})$/;
unlike('', $pattern, 'surrounded overloaded array rejects empty subject');
unlike('XAB-B-=EC', $pattern,
    'surrounded overloaded array preserves separator and case transform');
}

done_testing;
