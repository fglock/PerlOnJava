use strict;
use warnings;
use Test::More;

plan skip_all => 'GH 20653 semantics require Perl 5.44+' if $] < 5.044;

ok('bbab' =~ /(?|(?<a>a)|(?<b>b))\1(?&a)(?&b)/,
    'branch-reset numbered and named subroutine targets share captures');
ok('byb' =~ /(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1/,
    'named condition sees unset sibling capture');
ok(!('bxb' =~ /(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1/),
    'named condition rejects wrong unset-capture branch');
ok('axa' =~ /(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1/,
    'named condition sees set capture');

'a' =~ /(?|(?<a>a)|(?<b>b))/;
is("$1-$+{a}-" . (defined $+{b} ? $+{b} : ''), 'a-a-',
    'first branch publishes only its named capture');
'b' =~ /(?|(?<a>a)|(?<b>b))/;
is("$1-" . (defined $+{a} ? $+{a} : '') . "-$+{b}", 'b--b',
    'second branch publishes only its named capture');

for my $case (
    ['preabcpost', 'a-b-c'],
    ['predepost', 'd-e-'],
    ['prefpost', 'f--'],
) {
    my ($subject, $expected) = @$case;
    $subject =~ /(?<pre>pre)(?|(?<a>a)(?<b>b)(?<c>c)|(?<d>d)(?<e>e)|(?<f>f))(?<post>post)/;
    is("$2-" . (defined $3 ? $3 : '') . '-' . (defined $4 ? $4 : ''),
        $expected, 'branch-reset physical slots preserve post-group numbering');
}

for my $letter (qw(a b c)) {
    my $subject = $letter x 2;
    ok($subject =~ /((?|(?<a>a)(?-1)|(?<b>b)(?-1)|(?<c>c)(?-1)))/,
        'relative subroutine target resolves inside each branch-reset alternative');
    is($1, $subject, 'relative subroutine target consumes the matching pair');
}

done_testing;
