use strict;
use warnings;
use Test::More;
use Clone qw(clone);
use Scalar::Util qw(refaddr);

my $value = 'x';
my $shared = \$value;
my $input = [$shared, \$value];
my $copy = clone($input);

is(refaddr($copy->[0]), refaddr($copy->[1]),
    'cloned elements retain their shared scalar referent');
isnt(refaddr($copy->[0]), refaddr($input->[0]),
    'cloned referent is independent of the source');

my $replacement = 'replacement';
$copy->[0] = \$replacement;
is(${$copy->[0]}, 'replacement', 'first cloned array slot accepts a replacement');
is(${$copy->[1]}, 'x', 'replacing one cloned slot leaves the other slot intact');

our @destroyed;
{
    my $source = { name => 'source' };
    my $cloned = clone($source);
    $cloned->{name} = 'copy';
    bless $source, 'CloneReferenceGraphLifetime';
    bless $cloned, 'CloneReferenceGraphLifetime';
}
is_deeply([sort @destroyed], [qw(copy source)],
    'source and cloned object are destroyed at lexical scope exit');

done_testing;

package CloneReferenceGraphLifetime;
sub DESTROY { push @main::destroyed, $_[0]{name} }
