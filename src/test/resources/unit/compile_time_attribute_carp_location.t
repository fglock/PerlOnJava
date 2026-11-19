use 5.018;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use lib 'src/test/resources/unit/lib';
use Attribute::RecordCallers;
use Test::More tests => 3;

my @warnings;
BEGIN { $SIG{__WARN__} = sub { push @warnings, @_ }; }

my $calls = 0;
my sub foo :RecordCallers { $calls++ }

foo();
is($calls, 1, 'the attributed lexical sub remains callable');
is(scalar @warnings, 1, 'the attribute handler emits one warning');
like($warnings[0],
    qr/^Ignoring RecordCallers attribute on anonymous subroutine at .*compile_time_attribute_carp_location\.t line \d+\./,
    'Carp reports the Perl declaration source location');
