use strict;
use warnings;
use Test::More tests => 9;
use File::Temp qw(tempdir);
use Scalar::Util qw(refaddr weaken);

{
    my %hash = (alpha => 1, beta => 2, gamma => 3);
    my @seen;
    while (my ($key, $value) = each %hash) {
        push @seen, $key;
        delete $hash{$key};
    }
    is scalar(@seen), 3,
        'deleting the current plain-hash key does not invalidate each';
}

{
    package Local::Context;

    sub new {
        my $self = bless { callbacks => [] }, shift;
        my $this = $self;
        Scalar::Util::weaken($this);
        push @{$self->{callbacks}}, sub { return $this };
        return $self;
    }

    sub set_parent {
        my ($self, $parent) = @_;
        $self->{parent} = $parent;
        Scalar::Util::weaken($self->{parent});
    }

    sub DESTROY { }
}

{
    package Local::OrderedHash;

    sub TIEHASH {
        return bless [ {}, [], [] ], shift;
    }

    sub STORE {
        my ($self, $key, $value) = @_;
        if (!exists $self->[0]{$key}) {
            $self->[0]{$key} = scalar @{$self->[1]};
            push @{$self->[1]}, $key;
        }
        $self->[2][$self->[0]{$key}] = $value;
    }

    sub FETCH {
        my ($self, $key) = @_;
        return undef unless exists $self->[0]{$key};
        return $self->[2][$self->[0]{$key}];
    }
}

{
    tie my %top, 'Local::OrderedHash';
    my $outer = Local::Context->new;
    $top{outer} = $outer;

    tie my %children, 'Local::OrderedHash';
    $outer->{children} = \%children;
    my $inner = Local::Context->new;
    $inner->set_parent($outer);
    $children{inner} = $inner;

    undef $inner;
    undef $outer;
    my $stored_inner = $top{outer}{children}{inner};
    ok defined($stored_inner->{parent}),
        'nested tied context keeps its weak parent through temporary cleanup';
}

{
    tie my %ordered, 'Local::OrderedHash';
    my $owner = bless {}, 'Local::Owner';
    my $owner_addr = refaddr($owner);
    $ordered{owner} = $owner;

    my $backref = $owner;
    weaken($backref);
    undef $owner;

    ok defined($backref),
        'pure-Perl tied storage remains a strong owner of a weak referent';
    is refaddr($ordered{owner}), $owner_addr,
        'tied storage preserves reference identity';
    is refaddr($backref), $owner_addr,
        'weak reference still points at the tied container value';
}

{
    my $dir = tempdir(CLEANUP => 1);
    my $script = "$dir/global_tie_destroy.pl";
    my $marker = "$dir/destroyed";
    open my $fh, '>', $script or die "open $script: $!";
    print {$fh} <<'CHILD';
use strict;
use warnings;
{
    package Local::ShutdownHash;
    sub TIEHASH { bless { marker => $_[1] }, $_[0] }
    sub DESTROY {
        open my $out, '>', $_[0]{marker} or die $!;
        print {$out} "destroyed\n";
        close $out or die $!;
    }
}

tie our %shutdown_hash, 'Local::ShutdownHash', $ARGV[0];
CHILD
    close $fh or die "close $script: $!";
    my $status = system($^X, $script, $marker);
    ok $status == 0 && -s $marker,
        'global tied hash releases its handler at shutdown';
}

{
    my $dir = tempdir(CLEANUP => 1);
    my $module = "$dir/runtime_child.pm";
    open my $fh, '>', $module or die "open $module: $!";
    print {$fh} <<'MODULE';
package Local::RuntimeParent;
sub new { bless {}, $_[0] }

package Local::RuntimeChild;
our @ISA = ('Local::RuntimeParent');
our $singleton = __PACKAGE__->SUPER::new;
1;
MODULE
    close $fh or die "close $module: $!";

    sub load_runtime_child {
        require $module;
        no warnings 'once';
        return $Local::RuntimeChild::singleton;
    }

    isa_ok load_runtime_child(), 'Local::RuntimeChild',
        'top-level SUPER resolves lexically when require runs inside a sub';
}

{
    package Local::Columns;
    sub get_columns { return (id => 1, name => 'A', color => 'purple') }
}

{
    my @rows = (bless({}, 'Local::Columns'));
    my $flattened = [ map { { $_->get_columns } } @rows ];
    is ref($flattened->[0]), 'HASH',
        'map block preserves an inner anonymous hash constructor';
    is_deeply $flattened->[0], { id => 1, name => 'A', color => 'purple' },
        'method-returned key/value pairs populate the anonymous hash';
}
