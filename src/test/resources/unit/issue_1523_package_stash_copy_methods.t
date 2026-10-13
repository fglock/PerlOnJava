use strict;
use warnings;
use Test::More;

{
    package Issue1523::CopyTarget;
    sub new { bless { value => $_[1] }, $_[0] }
    sub value { $_[0]->{value} }
}

{
    no strict 'refs';
    my %saved = %{"Issue1523::CopyTarget::"};
    %{"Issue1523::CopyTarget::"} = %saved;
}

my $object = Issue1523::CopyTarget->new('restored');
isa_ok($object, 'Issue1523::CopyTarget', 'constructor still dispatches after stash restore');
is($object->value, 'restored', 'copied stash retains ordinary methods');

done_testing;
