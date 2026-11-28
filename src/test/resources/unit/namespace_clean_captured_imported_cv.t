use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Moo; require namespace::clean; 1 }
        or plan skip_all => 'Moo and namespace::clean are required';
}

my $has = eval q{
    package NamespaceCleanCapturedImportedCV;
    use Moo;
    my $has = \&has;
    use namespace::clean;
    return $has;
};
ok($has, 'eval returns the captured imported has CV');

my $installed = eval { $has->('foo', is => 'ro'); 1 };
ok($installed, 'captured imported CV remains callable after namespace cleanup');
diag($@) unless $installed;

my $value = eval {
    NamespaceCleanCapturedImportedCV->new(foo => 'captured-cv')->foo;
};
is($value, 'captured-cv', 'captured CV installs the Moo accessor');
diag($@) if $@;

done_testing;
