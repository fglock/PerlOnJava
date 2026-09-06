use strict;
use warnings;
use Test::More;

{
    package CountFetch;
    sub TIESCALAR { bless { value => $_[1], fetches => 0 }, $_[0] }
    sub FETCH { ++$_[0]{fetches}; $_[0]{value} }
}

tie my $tied, 'CountFetch', 'z';
my $object = tied $tied;
my @range = $tied .. 'a';
is($object->{fetches}, 1, 'range fetches its tied left endpoint once');

tie $tied, 'CountFetch', 'z';
$object = tied $tied;
@range = 'a' .. $tied;
is($object->{fetches}, 1, 'range fetches its tied right endpoint once');

done_testing();
