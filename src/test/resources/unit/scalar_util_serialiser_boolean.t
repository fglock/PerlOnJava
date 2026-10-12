use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(looks_like_number);

# Types::Serialiser represents JSON booleans as blessed scalar references
# with numeric overloads. Keep the shape here so the test has no CPAN
# dependency while covering the scalar behavior used by its predicates.
{
    package Local::Issue1419::Boolean;
    use overload '0+' => sub { ${$_[0]} }, fallback => 1;
}

my $true = bless \do { my $value = 1 }, 'Local::Issue1419::Boolean';
my $false = bless \do { my $value = 0 }, 'Local::Issue1419::Boolean';
ok(looks_like_number($true), 'blessed scalar true value is numeric');
ok(looks_like_number($false), 'blessed scalar false value is numeric');
is(0 + $true, 1, 'blessed scalar true value retains numeric truth');
is(0 + $false, 0, 'blessed scalar false value retains numeric falsehood');
