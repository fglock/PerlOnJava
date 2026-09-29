use strict;
use warnings;
use Test::More;

{
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    bless [], '';
    like($warnings[0] // '', qr/^Explicit blessing to '' \(assuming package main\)/,
         'empty explicit package warns before defaulting to main');
}

sub bless_freed_package_implicit { package BlessFreedPackage; bless [] }
delete $::{'BlessFreedPackage::'};

my $error = eval { bless_freed_package_implicit(); 1 } ? '' : $@;
like($error, qr/^Attempt to bless into a freed package/,
     'implicit bless rejects a deleted current package stash');

my $explicit = eval { bless [], 'BlessFreedPackage' };
is($@, '', 'explicit bless recreates a deleted package stash');
is(ref($explicit), 'BlessFreedPackage', 'explicit bless uses the recreated package');

for (__PACKAGE__) {
    my $readonly_error = eval { bless \$_; 1 } ? '' : $@;
    like($readonly_error, qr/^Modification of a read-only value attempted/,
         'blessing a readonly foreach scalar fails');
}

done_testing;
