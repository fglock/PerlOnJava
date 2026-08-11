use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Package::Stash; 1 }
        or plan skip_all => 'Package::Stash required';
}

plan tests => 2;

{
    package PackageStashConstantCode;
    use constant ROLE_METHOD => 'role method';
}

my $stash = Package::Stash->new('PackageStashConstantCode');
my $code  = $stash->get_symbol('&ROLE_METHOD');

ok(ref($code) eq 'CODE', 'Package::Stash returns a constant CODE slot');
is($code->(), 'role method', 'constant CODE slot retains its value');
