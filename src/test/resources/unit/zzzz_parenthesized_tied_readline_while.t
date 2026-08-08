use strict;
use warnings;
use Test::More tests => 2;

tie *STORE, 'Local::Store';
print STORE "one\n";
print STORE "two\n";
my @result;
$result[0] .= $_ while (<STORE>);
untie *STORE;
is($result[0], "one\ntwo\n", 'parenthesized tied readline applies implicit while assignment');

tie *STORE, 'Local::Store';
print STORE "three\n";
print STORE "four\n";
$result[1] .= $_ while <STORE>;
untie *STORE;
is($result[1], "three\nfour\n", 'unparenthesized tied readline remains unchanged');

package Local::Store;
sub TIEHANDLE { bless [], shift }
sub PRINT { my $self = shift; push @$self, @_ }
sub READLINE { my $self = shift; shift @$self }
