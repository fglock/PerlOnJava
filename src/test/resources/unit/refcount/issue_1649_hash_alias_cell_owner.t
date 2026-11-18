use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use re 'eval';
use Scalar::Util qw(weaken refaddr);
use threads;
use Test::More;

{
    package Issue1649::HashAliasCellOwner;
    our %destroyed;
    our %dynamic_hash;
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

my $local_source = 10;
$hash{localized} = 0;
\$hash{localized} = \$local_source;
{
    local $hash{localized};
    $hash{localized} = 20;
    is($local_source, 10, 'localizing an alias writes to a temporary hash cell');
}
is($hash{localized}, 10, 'scope exit restores the original aliased cell');
$local_source = 30;
is($hash{localized}, 30, 'the restored hash slot still aliases its source');
delete $hash{localized};

my $exception_source = 5;
$hash{exception} = 0;
\$hash{exception} = \$exception_source;
eval {
    local $hash{exception};
    $hash{exception} = 7;
    die "unwind localized alias\n";
};
is($hash{exception}, 5, 'exception unwind restores a localized hash alias');
$exception_source = 9;
is($hash{exception}, 9, 'exception unwind preserves the original scalar cell');
delete $hash{exception};

my $package_source = 41;
$Issue1649::HashAliasCellOwner::dynamic_hash{slot} = 0;
\$Issue1649::HashAliasCellOwner::dynamic_hash{slot} = \$package_source;
{
    local %Issue1649::HashAliasCellOwner::dynamic_hash;
    $Issue1649::HashAliasCellOwner::dynamic_hash{temporary} = 2;
}
is($Issue1649::HashAliasCellOwner::dynamic_hash{slot}, 41,
    'localizing a package hash restores its aliased scalar cell');
$package_source = 42;
is($Issue1649::HashAliasCellOwner::dynamic_hash{slot}, 42,
    'restored package hash slot still aliases the original source');
undef %Issue1649::HashAliasCellOwner::dynamic_hash;

my %regex_hash;
my $weak_regex;
{
    my $source = bless { id => 'regex_rollback' }, 'Issue1649::HashAliasCellOwner';
    $weak_regex = $source;
    weaken($weak_regex);
    \$regex_hash{slot} = \$source;
}
ok('ab' !~ /^a(?{ delete $regex_hash{slot} })c$/,
    'failed regex callback abandons its hash deletion');
ok(defined $weak_regex, 'regex rollback retains the aliased scalar cell');
is($regex_hash{slot}{id}, 'regex_rollback',
    'regex rollback restores the original aliased cell');
is($Issue1649::HashAliasCellOwner::destroyed{regex_rollback} // 0, 0,
    'regex rollback prevents premature destruction');
undef %regex_hash;
ok(!defined $weak_regex, 'committed hash clear releases the restored alias');
is($Issue1649::HashAliasCellOwner::destroyed{regex_rollback} // 0, 1,
    'restored regex alias is destroyed once when cleared');

my $thread_source = 'parent';
my %thread_hash;
\$thread_hash{slot} = \$thread_source;
my $thread = threads->create(sub {
    my $same_cell = refaddr(\$thread_hash{slot}) == refaddr(\$thread_source);
    $thread_hash{slot} = 'child';
    return join(':', $same_cell, $thread_source);
});
is($thread->join, '1:child', 'thread cloning preserves a hash scalar alias');
is($thread_source, 'parent', 'thread writes stay in the cloned scalar cell');
is($thread_hash{slot}, 'parent', 'thread clone leaves the parent hash unchanged');

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

my ($weak_deleted_return, $deleted_return);
{
    my $source = bless { id => 'deleted_return' }, 'Issue1649::HashAliasCellOwner';
    $weak_deleted_return = $source;
    weaken($weak_deleted_return);
    \$hash{deleted_return} = \$source;
}
$deleted_return = delete $hash{deleted_return};
ok(defined $weak_deleted_return,
    'delete result keeps an aliased referent alive as an ordinary value');
is($Issue1649::HashAliasCellOwner::destroyed{deleted_return} // 0, 0,
    'delete does not destroy a referent still held by its result');
undef $deleted_return;
ok(!defined $weak_deleted_return,
    'dropping the delete result releases the aliased referent');
is($Issue1649::HashAliasCellOwner::destroyed{deleted_return} // 0, 1,
    'delete result referent is destroyed once');

my $weak_duplicate;
{
    my $source = bless { id => 'duplicate' }, 'Issue1649::HashAliasCellOwner';
    $weak_duplicate = $source;
    weaken($weak_duplicate);
    \$hash{duplicate_a} = \$source;
    \$hash{duplicate_b} = \$source;
}
ok(defined $weak_duplicate, 'two hash slots retain the same aliased scalar cell');
delete $hash{duplicate_a};
ok(defined $weak_duplicate, 'deleting one of two aliases retains the shared cell');
delete $hash{duplicate_b};
ok(!defined $weak_duplicate, 'deleting the last alias releases the shared cell');
is($Issue1649::HashAliasCellOwner::destroyed{duplicate} // 0, 1,
    'duplicate hash aliases destroy their referent once');

my $weak_first_weaken;
{
    my $source = bless { id => 'first_weaken' }, 'Issue1649::HashAliasCellOwner';
    $weak_first_weaken = $source;
    weaken($weak_first_weaken);
    \$hash{first_weaken} = \$source;
    weaken($source);
}
ok(!defined $weak_first_weaken,
    'a hash alias does not strengthen a scalar cell weakened after storage');
ok(!defined $hash{first_weaken}, 'the shared hash cell observes the weakened undef');
is($Issue1649::HashAliasCellOwner::destroyed{first_weaken} // 0, 1,
    'weakening before source scope exit destroys the referent once');
delete $hash{first_weaken};

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

my $weak_undefined;
{
    my $source = bless { id => 'undefined_hash' }, 'Issue1649::HashAliasCellOwner';
    $weak_undefined = $source;
    weaken($weak_undefined);
    \$hash{undefined_hash} = \$source;
}
undef %hash;
ok(!defined $weak_undefined, 'undefining a hash releases aliased scalar cells');
is($Issue1649::HashAliasCellOwner::destroyed{undefined_hash} // 0, 1,
    'undefining aliased cells destroys their referents once');

done_testing();
