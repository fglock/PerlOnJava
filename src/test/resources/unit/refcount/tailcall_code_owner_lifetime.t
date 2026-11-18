use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

sub dispatch_tail {
    my ($target) = @_;
    goto &$target;
}

my $answer = 42;
my $target = sub { $answer };
my $weak_target = $target;
weaken($weak_target);

is(dispatch_tail($target), 42, 'tail call returns the target result');
ok(defined $weak_target, 'the caller lexical keeps CODE alive after the tail call');
undef $target;
ok(!defined $weak_target, 'the final lexical release clears the weak CODE reference');
