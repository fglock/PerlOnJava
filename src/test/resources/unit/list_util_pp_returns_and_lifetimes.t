use strict;
use warnings;
use Test::More;
use FindBin;
use lib "$FindBin::Bin/fixtures/MaybeXS/lib";
use List::Util::PP qw(first reduce reductions uniqstr);

my $first_return = first { return ($_ > 6) } 2, 4, 6, 12;
is($first_return, 12, 'first honors return from its block');
my $first_loop_return = first { while (1) { return ($_ > 6) } } 2, 4, 6, 12;
is($first_loop_return, 12,
    'first honors return from a loop in its block');
my $reduce_return = reduce { return $a + $b } 2, 4, 6;
is($reduce_return, 12,
    'reduce honors return from its block');
my $reduce_loop_return = reduce { while (1) { return $a + $b } } 2, 4, 6;
is($reduce_loop_return, 12,
    'reduce honors return from a loop in its block');

SKIP: {
    skip 'Internals::SvREFCNT is unavailable', 1 if !defined &Internals::SvREFCNT;
    sub issue2000_refcounted_callback { $_ > 1E6 }
    my $before = &Internals::SvREFCNT(\&issue2000_refcounted_callback);
    &List::Util::PP::first(\&issue2000_refcounted_callback, 1 .. 6);
    is(&Internals::SvREFCNT(\&issue2000_refcounted_callback), $before,
        'first leaves callback refcount unchanged');
}

our $destroyed_count = 0;
{
    package Issue2000::Guardian;
    sub DESTROY { $main::destroyed_count++ }
}
{
    my @input = map { bless [], 'Issue2000::Guardian' } 1 .. 5;
    my @results = reductions { $b } @input;
    undef @input;
    is($destroyed_count, 0, 'reductions retains values returned to the caller');
    @results = ();
    is($destroyed_count, 5, 'reductions results are destroyed after the caller releases them');
}

{
    package Issue2000::DestroyNotifier;
    use overload '""' => sub { 'SAME' };
    sub new { bless { counter => $_[1] }, $_[0] }
    sub DESTROY { ${$_[0]->{counter}}++ }
}
{
    my @counts = (0, 0, 0);
    my @input = map { Issue2000::DestroyNotifier->new(\$counts[$_]) } 0 .. 2;
    my @unique = uniqstr @input;
    undef @input;
    is_deeply(\@counts, [0, 1, 1], 'uniqstr destroys values it filters out');
    undef @unique;
    is_deeply(\@counts, [1, 1, 1], 'uniqstr result is destroyed when released');
}

done_testing;
