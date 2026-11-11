use strict;
use warnings;
use Test::More tests => 2;

no strict;
$DeletedNamespaceRead::GEN0::test = 1;
my $hash = $DeletedNamespaceRead::{"GEN0::"};
for my $key (keys %$hash) { undef $hash->{$key} }
%$hash = ();
delete $DeletedNamespaceRead::{"GEN0::"};
my $value = $DeletedNamespaceRead::GEN0::test;
ok !defined($value), 'reading deleted namespace scalar returns undef';
ok !exists $DeletedNamespaceRead::{"GEN0::"}, 'reading deleted namespace scalar does not recreate stash entry';
