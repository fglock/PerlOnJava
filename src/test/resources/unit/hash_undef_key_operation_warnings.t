use strict;
use warnings;
use Test::More;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };
my %hash;
my $key;

{
    no warnings;
    use warnings qw(uninitialized);
    my $read = $hash{$key};
    my $exists = exists $hash{$key};
    my $deleted = delete $hash{$key};
}

is(scalar @warnings, 3,
   'hash read, exists, and delete each warn for an undefined key');
like($warnings[0], qr/Use of uninitialized value.*hash element/,
     'hash read reports an uninitialized key');
like($warnings[1], qr/Use of uninitialized value.*exists/,
     'exists reports an uninitialized key');
like($warnings[2], qr/Use of uninitialized value.*delete/,
     'delete reports an uninitialized key');

@warnings = ();
{
    no warnings;
    my $exists = exists $hash{$key};
    my $deleted = delete $hash{$key};
}
is(scalar @warnings, 0,
   'disabled uninitialized warnings remain silent for exists and delete');

@warnings = ();
{
    no warnings;
    use warnings qw(uninitialized);
    my $autovivified;
    delete $autovivified->{$key};
}
is(scalar @warnings, 1,
   'delete warns once when its hash reference is autovivified');
like($warnings[0], qr/Use of uninitialized value.*delete/,
     'autovivified delete reports the undefined key');

done_testing;
