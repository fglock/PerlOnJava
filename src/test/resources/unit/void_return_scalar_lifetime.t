use strict;
use warnings;
use Test::More tests => 5;

my @destroyed;
{
    package VoidReturnLifetime;
    sub DESTROY { push @destroyed, $_[0]{name} }
}

sub discarded_object {
    return bless { name => 'discarded' }, 'VoidReturnLifetime';
}

discarded_object();
is_deeply(\@destroyed, ['discarded'], 'discarded object return is destroyed after a void call');

my $source = 7;
sub discarded_scalar { return $source }
discarded_scalar();
is($source, 7, 'void scalar return leaves its source unchanged');

sub copied_scalar { return $source }
my $copy = copied_scalar();
$copy = 9;
is($source, 7, 'scalar-context return remains an independent scalar copy');

my $reference = [1];
sub discarded_reference { return $reference }
discarded_reference();
is_deeply($reference, [1], 'void reference return leaves the source referent intact');

my $returned_reference = discarded_reference();
push @$returned_reference, 2;
is_deeply($reference, [1, 2], 'scalar-context reference return keeps reference identity');
