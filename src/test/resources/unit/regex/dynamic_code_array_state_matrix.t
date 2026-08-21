use strict;
use warnings;
use Test::More;
use Tie::Array;
use re 'eval';

{
    package DynamicCodeArrayStateMatrix::TiedScalar;
    our $fetches = 0;
    sub TIESCALAR { bless [undef], __PACKAGE__ }
    sub STORE { $_[0][0] = $_[1] }
    sub FETCH { ++$fetches; $_[0][0] }
}

my @array;
our @global;
my @refs = (0, \@array, 2);
tie my @tied, 'Tie::StdArray';
{
    my $bb = 'B';
    my $dd = 'D';
    @array = ('A', qr/(??{$bb})/, 'C', qr/(??{$dd})/, 'E');
    @global = @array;
    @tied = @array;
}

like('A B C D E', qr/^@array$/, 'lexical array preserves executable qr elements');
like('A B C D E', qr/^@global$/, 'package array preserves executable qr elements');
like('A B C D E', qr/^@{$refs[1]}$/, 'array reference preserves executable qr elements');
like('A B C D E', qr/^@tied$/, 'tied array preserves executable qr elements');

{
    local $" = '-';
    like('A-B-C-D-E', qr/^@array$/,
        'localized separator composes lexical executable elements');
    like('A-B-C-D-E', qr/^@tied$/,
        'localized separator composes tied executable elements');
}

{
    my $leaf = 'Z';
    my $inner = qr/(??{$leaf})/;
    my $nested = qr/(??{$inner})/;
    my @nested = ('N', $nested, 'X');
    like('N Z X', qr/^@nested$/,
        'array element can return a second executable qr');
    $leaf = 'Q';
    like('N Q X', qr/^@nested$/,
        'nested executable array element retains its lexical cell');
}

{
    my @mutations;
    my $branch = qr/(?{ push @mutations, 'failed' })b/;
    my @parts = ($branch);
    ok('ac' =~ /^(?:a@parts|a(?{ push @mutations, 'kept' })c)$/,
        'failed executable array branch backtracks to a later alternative');
    is_deeply(\@mutations, ['failed', 'kept'],
        'interpolated qr callback mutation survives branch backtracking');
}

{
    my $message = 'array callback exploded';
    my @bad = (qr/(??{ die $message })/);
    my $error = '';
    eval { 'x' =~ /^@bad$/ };
    $error = $@;
    like($error, qr/array callback exploded/,
        'exception from executable array element propagates');

    my $value = 'x';
    my @good = (qr/(??{$value})/);
    like('x', qr/^@good$/,
        'a later executable array remains usable after an exception');
}

{
    my $token = '[a-z]';
    my @parts = (qr/(??{$token})/);
    my $subject = 'a1b';
    my @seen;
    push @seen, $& while $subject =~ /@parts/g;
    is(join('', @seen), 'ab', '/g reuses executable array source');

    pos($subject) = 1;
    ok(!($subject =~ /\G@parts/gc), '/gc executable array can fail at pos');
    is(pos($subject), 1, '/c preserves pos after executable array failure');
    $token = '1';
    ok($subject =~ /\G@parts/gc && $& eq '1',
        '/gc executable array reuses its callback at preserved pos');
}

{
    my $value = 'a';
    my @parts = (qr/(??{$value})/);
    sub match_once_array { $_[0] =~ /^@parts$/o }

    ok(match_once_array('a'), '/o compiles the executable array once');
    $value = 'b';
    @parts = (qr/c/);
    ok(match_once_array('b'), '/o retains the original callback lexical cell');
    ok(!match_once_array('c'), '/o does not replace the original array template');
}

{
    my $value = 'T';
    tie my $slot, 'DynamicCodeArrayStateMatrix::TiedScalar';
    $slot = qr/(??{$value})/;
    $DynamicCodeArrayStateMatrix::TiedScalar::fetches = 0;
    like('T', qr/^$slot$/, 'tied scalar can supply an executable qr');
    is($DynamicCodeArrayStateMatrix::TiedScalar::fetches, 1,
        'tied executable scalar is fetched exactly once');
}

{
    my $trusted = 'Q';
    my $runtime = 'R';
    my @mixed = ('L', qr/(??{$trusted})/, '(??{$runtime})');
    like('L Q R', qr/^@mixed$/,
        'array composes trusted qr and runtime callback source');
    $trusted = 'S';
    $runtime = 'T';
    like('L S T', qr/^@mixed$/,
        'mixed array source retains both lexical cells');

    tie my @mixed_tied, 'Tie::StdArray';
    @mixed_tied = @mixed;
    local $" = '-';
    like('L-S-T', qr/^@mixed_tied$/,
        'tied mixed array composes under a localized separator');
}

{
    my @invalid = ('(?{ this is not perl })');
    my $error = '';
    eval { qr/@invalid/ };
    $error = $@;
    like($error, qr/syntax error|Bareword/,
        'invalid runtime source reports a compilation exception');

    my $value = 'recovered';
    my @valid = ('(??{$value})');
    like('recovered', qr/^@valid$/,
        'runtime source compiler recovers after a compilation exception');
}

done_testing;
