use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 7;

{
    package Local::ScalarReferenceContentsRetarget;
    our $destroyed = 0;

    sub new {
        my ($class) = @_;
        return bless {}, $class;
    }

    sub DESTROY { ++$destroyed }
}

sub return_scalar_cell {
    my $value = Local::ScalarReferenceContentsRetarget->new;
    return \$value;
}

my $cell_ref = return_scalar_cell();
my $weak_first = $$cell_ref;
weaken($weak_first);
is($Local::ScalarReferenceContentsRetarget::destroyed, 0,
    'returned scalar reference keeps its current contents alive');
ok(defined $weak_first, 'first referent remains alive after its lexical scope exits');

my $second = Local::ScalarReferenceContentsRetarget->new;
my $weak_second = $second;
weaken($weak_second);
$$cell_ref = $second;
undef $second;

is($Local::ScalarReferenceContentsRetarget::destroyed, 1,
    'overwriting the referenced scalar releases its previous contents');
ok(!defined $weak_first, 'previous contents weak reference clears on replacement');
ok(defined $weak_second, 'replacement contents remain owned by the scalar reference');

undef $cell_ref;
is($Local::ScalarReferenceContentsRetarget::destroyed, 2,
    'releasing the scalar reference destroys the replacement contents once');
ok(!defined $weak_second, 'final scalar-reference release clears the replacement weak observer');

done_testing;
