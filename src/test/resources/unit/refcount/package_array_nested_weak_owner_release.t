use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

our @PACKAGE_OWNER_ROOT;

my $target = [];
push @PACKAGE_OWNER_ROOT, $target;
my $observer = $target;
weaken($observer);
undef $target;

ok(isweak($observer), 'package array slot keeps the nested aggregate strongly owned');
ok(defined($observer), 'weak observer remains live while the package slot exists');

pop @PACKAGE_OWNER_ROOT;

ok(!defined($observer), 'removing the final package slot releases the aggregate');
ok(!isweak($observer), 'the weak observer clears with the final package owner');

done_testing;
