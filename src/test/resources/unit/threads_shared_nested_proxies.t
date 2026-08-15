use strict;
use warnings;
use threads;
use threads::shared;
use Scalar::Util qw(refaddr weaken);

print "1..10\n";
my $test = 0;
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

{
    package SharedInner;
    sub value { $_[0]{value} }
}

{
    package SharedPublished;
    sub value { $_[0]{value} }
}

my $source = { inner => bless({ value => 1 }, 'SharedInner') };
my $shared = shared_clone($source);
ok($shared->{inner}->value == 1, 'shared_clone preserves nested content');
ok($source->{inner}{value} == 1 && !is_shared($source),
    'shared_clone leaves its source isolated');

my $first = $shared->{inner};
my $second = $shared->{inner};
ok(refaddr($first) != refaddr($second), 'each nested fetch returns a fresh proxy view');
bless($first, 'SharedPublished');
ok(ref($shared->{inner}) eq 'SharedInner', 'local reblessing is not published by fetch');
$first->{value} = 7;
ok($shared->{inner}{value} == 7, 'proxy mutations use common shared backing');
$shared->{inner} = $first;
ok(ref($shared->{inner}) eq 'SharedPublished', 'storing a proxy publishes its blessing');

my $cycle = shared_clone({});
$cycle->{self} = $cycle;
my $cycle_view = $cycle->{self};
ok(refaddr($cycle_view) != refaddr($cycle), 'cycle traversal returns a fresh proxy view');
$cycle_view->{seen} = 1;
ok($cycle->{seen} == 1, 'cycle views retain common backing');

my $weak_target = $shared->{inner};
weaken($weak_target);
ok(!defined($weak_target), 'an otherwise unowned weak proxy view is released locally');
ok($shared->{inner}{value} == 7,
    'releasing a local weak view does not remove canonical shared storage');
