use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 6;

our $DESTROYED = 0;
{
    package ListAssignmentTiedHashExceptionOwner;
    sub DESTROY { ++$main::DESTROYED }
}

{
    package ListAssignmentTiedHashException;
    sub TIEHASH { bless { values => {} }, $_[0] }
    sub CLEAR { %{$_[0]{values}} = (); return }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub STORE {
        my ($self, $key, $value) = @_;
        die "rejected tied hash store\n" if $key eq 'new';
        $self->{values}{$key} = $value;
    }
}

my $old = bless {}, 'ListAssignmentTiedHashExceptionOwner';
my $old_weak = $old;
weaken($old_weak);
my %tail;
tie %tail, 'ListAssignmentTiedHashException';
$tail{old} = $old;
undef $old;
ok(defined $old_weak, 'the tied hash retains its original value');

my $replacement = bless {}, 'ListAssignmentTiedHashExceptionOwner';
my $replacement_weak = $replacement;
weaken($replacement_weak);
my @rhs = (1, new => $replacement);
my $prefix;
my $error = '';
eval { ($prefix, %tail) = @rhs; 1 } or $error = $@;
like($error, qr/rejected tied hash store/, 'the tied STORE exception propagates');
ok(!defined $old_weak, 'tied CLEAR releases the displaced hash value');
ok(defined $replacement_weak, 'the RHS array still owns its replacement');

undef @rhs;
undef $replacement;
ok(!defined $replacement_weak, 'failed assignment leaves no temporary RHS owner');
is($DESTROYED, 2, 'both displaced and rejected values are destroyed exactly once');
