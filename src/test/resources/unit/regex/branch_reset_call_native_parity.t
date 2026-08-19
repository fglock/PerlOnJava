use strict;
use warnings;
use Test::More;

sub check {
    my ($pattern, $yes1, $yes2, $no1, $no2, $label) = @_;
    my $regex = eval { qr/$pattern/ };
    ok(defined($regex), "$label compiles") or diag($@);
    ok($yes1 =~ $regex, "$label first branch matches");
    is($1, substr($yes1, 0, 1), "$label first branch capture");
    ok($yes2 =~ $regex, "$label second branch matches");
    is($1, substr($yes2, 0, 1), "$label second branch capture");
    ok($no1 !~ $regex, "$label rejects first wrong call target");
    ok($no2 !~ $regex, "$label rejects second wrong call target");
}

check('(?|(?<d>1)|(?<d>2))(?&d)', '11', '21', '12', '22',
    'named ampersand');
check('(?|(?<d>1)|(?<d>2))(?P>d)', '11', '21', '12', '22',
    'named Python');
check('(?|(1)|(2))(?1)', '11', '21', '12', '22',
    'absolute numeric');

done_testing;
