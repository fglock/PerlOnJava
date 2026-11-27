use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

our $PACKAGE_OWNER = [];
my $weak = $PACKAGE_OWNER;

weaken($weak);
ok(isweak($weak), 'the local alias becomes weak');
ok(defined($weak), 'the package scalar keeps the referent alive');

undef $PACKAGE_OWNER;
ok(!defined($weak), 'removing the final package owner clears the weak alias');

done_testing;
