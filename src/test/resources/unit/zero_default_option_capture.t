use strict;
use warnings;
use Test::More;

my %options = (default => 0);
my $default;
if (exists $options{default}) {
    $default = $options{default};
}
my $getter = sub { $default };
is $getter->(), 0, 'zero default survives option lookup and closure capture';
done_testing;
