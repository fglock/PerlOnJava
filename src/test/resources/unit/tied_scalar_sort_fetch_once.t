use strict;
use warnings;
use Test::More;

my $fetches = 0;
sub TIESCALAR { my $package = shift; bless [@_], $package }
sub FETCH { ++$fetches; @{$_[0]} == 1 ? $_[0][0] : shift @{$_[0]} }
sub STORE { unshift @{$_[0]}, $_[1] }

tie my $tied, __PACKAGE__, 'main';
$tied = *dummy;
my $ignored = $tied;
$fetches = 0;
my $reference = \$tied;
eval '() = sort $reference 3, 2, 1';
is($fetches, 1, 'sort evaluates a tied scalar reached through a comparator reference once');

done_testing();
