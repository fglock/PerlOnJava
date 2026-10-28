use strict;
use warnings;
use Test::More tests => 6;

{
    package Local::ExistsResult;

    our $RESULT;

    sub TIEHASH { bless {}, shift }
    sub EXISTS { return $RESULT }
}

tie my %hash, 'Local::ExistsResult';

$Local::ExistsResult::RESULT = undef;
my $missing_undef = exists $hash{missing_undef};
ok defined($missing_undef), 'undef from tied EXISTS becomes a defined result';
is $missing_undef, '', 'undef from tied EXISTS becomes the empty-string false value';

$Local::ExistsResult::RESULT = 0;
my $missing_zero = exists $hash{missing_zero};
ok defined($missing_zero), 'zero from tied EXISTS becomes a defined result';
is $missing_zero, '', 'zero from tied EXISTS becomes the empty-string false value';

$Local::ExistsResult::RESULT = 'present';
my $present = exists $hash{present};
ok defined($present) && $present, 'truthy tied EXISTS result remains true';
is $present, 1, 'truthy tied EXISTS result is normalized to one';
