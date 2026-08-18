use strict;
use warnings;
use Test::More tests => 28;

local $" = '-';
my $pat;

my $A = 'A';
my $B = 'B';
my $C = 'C';
my $E = 'E';
my $a = 'aa';
my $b = 'bb';
my $c = 'cc';
my $e = 'ee';

my @r = (qr/(??{$B})/);

like('B', qr/^@r$/, 'single code regex in array');
like('AB', qr/^(??{$A})@r$/, 'literal code before array');
like('XAB', qr/^X(??{$A})@r$/, 'literal and code before array');
$pat = qr/^X(??{$A})@r(??{$C})$/;
like('XABC', $pat, 'code array between dynamic groups');
unlike('', $pat, 'combined pattern rejects empty string');
unlike('XAC', $pat, 'combined pattern requires array code');
unlike('XAbbC', $pat, 'combined pattern rejects wrong array code');

{
    my $B = 'Q';
    push @r, qr/(??{$B})/;
}

like('B-Q', qr/^@r$/, 'two code regexes in array');
like('AB-Q', qr/^(??{$A})@r$/, 'literal code before two-item array');
like('XAB-Q', qr/^X(??{$A})@r$/, 'literal before two-item array');
$pat = qr/^X(??{$A})@r(??{$C})$/;
like('XAB-QC', $pat, 'two-item array between dynamic groups');
unlike('', $pat, 'two-item pattern rejects empty string');
unlike('XAC', $pat, 'two-item pattern requires array code');
unlike('XAB-BC', $pat, 'two-item pattern preserves captured lexical');

package DynamicArrayLcConcat {
    use overload
        '""' => sub { ${$_[0]} },
        '.' => sub {
            my ($x, $y) = @_[ $_[2] ? (1, 0) : (0, 1) ];
            my ($xx, $yy) = ("$x", "$y");
            lc("$xx=$yy");
        };
}

my $r = qr/(??{$E})/;
bless $r, 'DynamicArrayLcConcat';

use re 'eval';

like('=ee', qr/^$r$/, 'overloaded scalar code pattern');
{
    no re 'eval';
    eval q{my $x = qr/^$r$/; 1};
    like($@, qr/Eval-group not allowed/, 'overloaded scalar needs re eval');
}
like('aa=ee', qr/^(??{$A})$r$/, 'dynamic code before overloaded scalar');
like('xaa=ee', qr/^X(??{$A})$r$/, 'literal before overloaded scalar');
$pat = qr/^X(??{$A})$r(??{$C})$/;
like('xaa=eeC', $pat, 'overloaded scalar between dynamic groups');
unlike('', $pat, 'overloaded scalar pattern rejects empty string');
unlike('XA=EC', $pat, 'overloaded scalar preserves concatenation case');

push @r, $r;

like('bb-bb-=ee', qr/^@r$/, 'overloaded scalar in code array');
{
    no re 'eval';
    eval q{my $x = qr/^@r$/; 1};
    like($@, qr/Eval-group not allowed/, 'overloaded array needs re eval');
}
like('aabb-bb-=ee', qr/^(??{$A})@r$/, 'dynamic code before overloaded array');
like('xaabb-bb-=ee', qr/^X(??{$A})@r$/, 'literal before overloaded array');
$pat = qr/^X(??{$A})@r(??{$C})$/;
like('xaabb-bb-=eeC', $pat, 'overloaded array between dynamic groups');
unlike('', $pat, 'overloaded array pattern rejects empty string');
unlike('XAB-B-=EC', $pat, 'overloaded array preserves concatenation case');
