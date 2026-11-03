use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package Issue1649::HashAliasCellOwner;
    our %destroyed;
    sub DESTROY { ++$destroyed{$_[0]{id}} }
}

my %hash;
my ($weak_old, $weak_replacement, $weak_deleted, $weak_cleared);
{
    my $source = bless { id => 'old' }, 'Issue1649::HashAliasCellOwner';
    $weak_old = $source;
    weaken($weak_old);
    \$hash{slot} = \$source;
    $source->{changed_through_source} = 1;
    is($hash{slot}{changed_through_source}, 1,
        'hash alias shares the source scalar cell');
}
ok(defined $weak_old, 'hash alias keeps the scalar cell alive after lexical exit');
is($Issue1649::HashAliasCellOwner::destroyed{old} // 0, 0,
    'hash alias prevents early destruction');

my $replacement_result;
{
    my $replacement = bless { id => 'replacement' }, 'Issue1649::HashAliasCellOwner';
    $weak_replacement = $replacement;
    weaken($weak_replacement);
    $replacement_result = eval {
        \$hash{slot} = \$replacement;
        1;
    };
    $replacement->{changed_through_source} = 2;
    is($replacement_result && ($hash{slot}{changed_through_source} // 0) == 2, 1,
        'rebound hash alias still shares the replacement cell');
}
ok($replacement_result, 'an existing hash slot can be rebound to another scalar cell')
    or diag($@);
ok(!defined $weak_old, 'rebinding releases the old aliased referent');
is($Issue1649::HashAliasCellOwner::destroyed{old} // 0, 1,
    'rebinding destroys the displaced referent once');
ok(defined $weak_replacement, 'rebound hash alias keeps its source alive after lexical exit');
$hash{slot} = undef;
ok(!defined $weak_replacement, 'assigning undef through the alias releases its referent');
is($Issue1649::HashAliasCellOwner::destroyed{replacement} // 0, 1,
    'rebound referent is destroyed once');

{
    my $source = bless { id => 'deleted' }, 'Issue1649::HashAliasCellOwner';
    $weak_deleted = $source;
    weaken($weak_deleted);
    \$hash{deleted} = \$source;
}
ok(defined $weak_deleted, 'new hash alias survives source lexical exit before delete');
delete $hash{deleted};
ok(!defined $weak_deleted, 'deleting the last hash alias releases its referent');
is($Issue1649::HashAliasCellOwner::destroyed{deleted} // 0, 1,
    'deleted alias referent is destroyed once');

{
    my $source = bless { id => 'cleared' }, 'Issue1649::HashAliasCellOwner';
    $weak_cleared = $source;
    weaken($weak_cleared);
    \$hash{cleared} = \$source;
}
ok(defined $weak_cleared, 'new hash alias survives source lexical exit before clear');
%hash = ();
ok(!defined $weak_cleared, 'clearing the hash releases its aliased scalar cell');
is($Issue1649::HashAliasCellOwner::destroyed{cleared} // 0, 1,
    'cleared alias referent is destroyed once');

done_testing();
